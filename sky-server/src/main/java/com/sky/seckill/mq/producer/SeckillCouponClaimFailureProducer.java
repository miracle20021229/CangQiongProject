package com.sky.seckill.mq.producer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 流程5领取失败治理生产者。
 * 同步发送成功后主消费者才 ACK，依靠 failureId 抵消“已发送但 ACK 前宕机”带来的重复消息。
 */
@Component
@Slf4j
public class SeckillCouponClaimFailureProducer {

    // 数据库治理摘要字段允许保存的最大字符数。
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    // 执行RocketMQ同步发送并返回Broker确认结果的模板。
    private final RocketMQTemplate rocketMQTemplate;
    // 隔离、待对账和死信事实统一进入的治理Topic。
    private final String failureTopic;

    /**
     * 注入RocketMQ模板和失败治理Topic配置。
     *
     * @param rocketMQTemplate RocketMQ发送模板
     * @param failureTopic     失败治理Topic名称
     */
    public SeckillCouponClaimFailureProducer(RocketMQTemplate rocketMQTemplate,
                                             @Value("${sky.rocketmq.seckill-coupon-claim.failure-topic:sky-seckill-coupon-claim-failure}")
                                             String failureTopic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.failureTopic = failureTopic;
    }

    /**
     * 构造稳定failureId并同步发送失败治理消息；只有Broker确认成功后方法才返回。
     *
     * @param sourceMessage   原始RocketMQ消息
     * @param claimMessage    已解析的领取消息，解析失败时可为空对象
     * @param rawBody         未修改的原始消息正文
     * @param failureCode     决定治理动作的稳定失败码
     * @param throwable       导致当前治理路由的异常
     * @param deliveryAttempts 原消息累计投递次数
     */
    public void publish(MessageExt sourceMessage, SeckillCouponClaimMessage claimMessage, String rawBody,
                        SeckillCouponClaimFailureCode failureCode, Throwable throwable, int deliveryAttempts) {
        if (sourceMessage == null || failureCode == null || !failureCode.getAction().requiresFailureRouting()) {
            throw new IllegalArgumentException("失败治理消息缺少来源或处理动作不合法");
        }
        //幂等解析数据
        String sourceMessageId = resolveSourceMessageId(sourceMessage);
        String sourceTopic = resolveSourceTopic(sourceMessage);
        String failureId = buildFailureId(
                claimMessage == null ? null : claimMessage.getClaimId(),
                sourceMessageId,
                sourceTopic,
                sourceMessage.getKeys(),
                rawBody,
                failureCode
        );
        //组装payload
        SeckillCouponClaimFailureAction action = failureCode.getAction();
        SeckillCouponClaimFailureMessage payload = SeckillCouponClaimFailureMessage.builder()
                .schemaVersion(SeckillCouponClaimConstant.FAILURE_MESSAGE_SCHEMA_VERSION)
                .failureId(failureId)
                .sourceMessageId(sourceMessageId)
                .sourceTopic(sourceTopic)
                .claimId(claimMessage == null ? null : claimMessage.getClaimId())
                .couponId(claimMessage == null ? null : claimMessage.getCouponId())
                .userId(claimMessage == null ? null : claimMessage.getUserId())
                .action(action)
                .errorCode(failureCode)
                .errorMessage(buildErrorMessage(throwable))
                .messageBody(rawBody)
                .deliveryAttempts(Math.max(deliveryAttempts, 1))
                .occurredAt(System.currentTimeMillis())
                .build();
        //同步发送消息
        Message<SeckillCouponClaimFailureMessage> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, failureId)
                .build();
        SendResult sendResult = rocketMQTemplate.syncSend(
                failureTopic + ":" + SeckillCouponClaimConstant.FAILURE_TAG,
                message
        );
        if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("秒杀券领取失败治理消息发送失败，failureId=" + failureId);
        }

        log.info("秒杀券领取失败已转入治理 Topic，failureId={}，msgId={}，action={}，errorCode={}",
                failureId, sendResult.getMsgId(), action, failureCode);
    }

    /**
     * 优先读取死信原始消息ID，确保同一消息跨重试和死信转发时保持稳定身份。
     *
     * @param sourceMessage 当前收到的RocketMQ消息
     * @return 可用于治理追踪的原始消息ID
     */
    private String resolveSourceMessageId(MessageExt sourceMessage) {
        return firstNotBlank(
                sourceMessage.getProperty(MessageConst.PROPERTY_DLQ_ORIGIN_MESSAGE_ID),
                sourceMessage.getProperty(MessageConst.PROPERTY_ORIGIN_MESSAGE_ID),
                sourceMessage.getMsgId()
        );
    }

    /**
     * 优先读取死信原始Topic，避免重试Topic或DLQ名称污染业务来源。
     *
     * @param sourceMessage 当前收到的RocketMQ消息
     * @return 原始业务Topic
     */
    private String resolveSourceTopic(MessageExt sourceMessage) {
        return firstNotBlank(
                sourceMessage.getProperty(MessageConst.PROPERTY_DLQ_ORIGIN_TOPIC),
                sourceMessage.getProperty(MessageConst.PROPERTY_REAL_TOPIC),
                sourceMessage.getTopic()
        );
    }

    /**
     * 根据稳定业务身份、来源Topic和失败码生成可重复计算的治理幂等ID。
     *
     * @param claimId        领取流水ID
     * @param sourceMessageId 原始消息ID
     * @param sourceTopic    原始业务Topic
     * @param keys           RocketMQ业务Key
     * @param rawBody        原始消息正文
     * @param failureCode    稳定失败码
     * @return UUID格式的稳定failureId
     */
    private String buildFailureId(String claimId, String sourceMessageId, String sourceTopic,
                                  String keys, String rawBody, SeckillCouponClaimFailureCode failureCode) {
        // claimId 和 Keys 跨重试投递保持稳定，优先于可能改变的 Broker msgId。
        String sourceIdentity = firstNotBlank(claimId, keys, sourceMessageId, rawBody, "UNKNOWN_SOURCE");
        String identity = firstNotBlank(sourceTopic, "UNKNOWN_TOPIC")
                + "|" + sourceIdentity
                + "|" + failureCode.name();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 把异常类型和消息压缩为符合数据库字段长度的治理摘要。
     *
     * @param throwable 原始异常
     * @return 截断后的异常摘要，无异常时返回null
     */
    private String buildErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String detail = throwable.getClass().getSimpleName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        return detail.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? detail
                : detail.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /**
     * 按参数顺序返回第一个非空白字符串，用于构造稳定消息来源身份。
     *
     * @param values 候选字符串
     * @return 第一个非空白值，全部为空时返回null
     */
    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
