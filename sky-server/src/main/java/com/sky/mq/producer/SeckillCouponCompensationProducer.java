package com.sky.mq.producer;

import com.sky.mq.message.SeckillCouponCompensationMessage;
import com.sky.mq.message.SeckillCouponCompensationMessage.CompensationType;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 流程2秒杀券Redis补偿消息生产者。
 */
@Component
@Slf4j
public class SeckillCouponCompensationProducer {

    private static final int MESSAGE_SCHEMA_VERSION = 1;
    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public SeckillCouponCompensationProducer(RocketMQTemplate rocketMQTemplate, @Value("${sky.rocketmq.seckill-coupon-compensation.topic:sky-seckill-coupon-compensation}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    /**
     * 尝试发送可领取列表缓存重建补偿消息。
     *
     * @param reason 触发补偿的业务场景
     * @return true表示发送成功，false表示发送失败并等待流程6兜底
     */
    public boolean trySendAvailableCacheRebuild(String reason) {
        try {
            send(CompensationType.AVAILABLE_CACHE_REBUILD, null, reason);
            return true;
        } catch (RuntimeException exception) {
            log.error("RocketMQ列表缓存补偿消息发送失败，reason={}，需要流程6对账兜底",
                    reason, exception);
            return false;
        }
    }

    /**
     * 尝试发送Redis活动快照同步补偿消息。
     *
     * @param couponId 秒杀券ID
     * @param reason   触发补偿的业务场景
     * @return true表示发送成功，false表示发送失败并等待流程6兜底
     */
    public boolean trySendActivitySnapshotSync(Long couponId, String reason) {
        if (couponId == null) {
            throw new IllegalArgumentException("发送活动快照补偿消息时couponId不能为空");
        }
        try {
            send(CompensationType.ACTIVITY_SNAPSHOT_SYNC, couponId, reason);
            return true;
        } catch (RuntimeException exception) {
            log.error("RocketMQ活动同步补偿消息发送失败，couponId={}，reason={}，需要流程6对账兜底",
                    couponId, reason, exception);
            return false;
        }
    }

    /**
     * 尝试发送Redis活动快照缺失修复消息。
     */
    public boolean trySendActivitySnapshotRepair(Long couponId, String reason) {
        if (couponId == null) {
            throw new IllegalArgumentException("发送活动修复补偿消息时couponId不能为空");
        }
        try {
            send(CompensationType.ACTIVITY_SNAPSHOT_REPAIR, couponId, reason);
            return true;
        } catch (RuntimeException exception) {
            log.error("RocketMQ活动修复补偿消息发送失败，couponId={}，reason={}，需要流程6对账兜底", couponId, reason, exception);
            return false;
        }
    }

    /**
     * 组装并同步发送补偿消息，发送结果非SEND_OK时抛出异常。
     */
    private void send(CompensationType type, Long couponId, String reason) {
        String eventId = UUID.randomUUID().toString();
        SeckillCouponCompensationMessage payload = SeckillCouponCompensationMessage.builder()
                .eventId(eventId)
                .schemaVersion(MESSAGE_SCHEMA_VERSION)
                .type(type)
                .couponId(couponId)
                .reason(reason)
                .occurredAt(System.currentTimeMillis())
                .build();
        Message<SeckillCouponCompensationMessage> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, eventId)
                .build();

        String destination = topic + ":" + type.name();
        SendResult sendResult = rocketMQTemplate.syncSend(destination, message);
        if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ补偿消息发送失败，eventId=" + eventId);
        }

        log.info("RocketMQ补偿消息发送成功，eventId={}，msgId={}，type={}，couponId={}",
                eventId, sendResult.getMsgId(), type, couponId);
    }
}
