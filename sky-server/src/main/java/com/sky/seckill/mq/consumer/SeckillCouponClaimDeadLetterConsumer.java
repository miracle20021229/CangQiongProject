package com.sky.seckill.mq.consumer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.producer.SeckillCouponClaimFailureProducer;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec.DecodeResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 流程5：监听原消费组的死信队列，将重试耗尽消息转入独立治理 Topic。
 * 本消费者不自动重放、不修改 Redis 或 MySQL 业务数据，对账修复属于流程6。
 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${sky.rocketmq.seckill-coupon-claim.dead-letter-topic:%DLQ%sky-seckill-coupon-claim-consumer}",
        consumerGroup = "${sky.rocketmq.seckill-coupon-claim.dead-letter-consumer-group:sky-seckill-coupon-claim-dlq-audit-consumer}",
        consumeThreadNumber = 1,
        consumeThreadMax = 4,
        maxReconsumeTimes = SeckillCouponClaimConstant.MAX_RECONSUME_TIMES
)
public class SeckillCouponClaimDeadLetterConsumer implements RocketMQListener<MessageExt> {

    // 将死信事实同步转发到独立治理Topic的生产者。
    private final SeckillCouponClaimFailureProducer failureProducer;
    // 统一解析主Topic、重试Topic和死信队列消息的编解码组件。
    private final SeckillCouponClaimMessageCodec messageCodec;

    /**
     * 注入死信转治理所需的生产者和原消息解析组件。
     *
     * @param failureProducer 失败治理消息生产者
     * @param messageCodec    原领取消息解析组件
     */
    public SeckillCouponClaimDeadLetterConsumer(SeckillCouponClaimFailureProducer failureProducer, SeckillCouponClaimMessageCodec messageCodec) {
        this.failureProducer = failureProducer;
        this.messageCodec = messageCodec;
    }

    /**
     * 接收主消费组重试耗尽的消息，并在治理消息发送成功后确认当前死信消息。
     *
     * @param messageExt RocketMQ原始死信消息
     */
    @Override
    public void onMessage(MessageExt messageExt) {
        if (messageExt == null) {
            throw new IllegalArgumentException("RocketMQ 死信消息为空");
        }

        DecodeResult decodeResult = messageCodec.decode(messageExt);
        int attemptCount = Math.max(messageExt.getReconsumeTimes() + 1, SeckillCouponClaimConstant.MAX_RECONSUME_TIMES + 1);

        RuntimeException cause = decodeResult.getDecodeException() == null ? new IllegalStateException("消息已耗尽 RocketMQ 重试次数") : decodeResult.getDecodeException();
        // 转发失败时抛出，让 DLQ 审计消费组自身继续重试。
        failureProducer.publish(messageExt, decodeResult.getMessage(), decodeResult.getRawBody(), SeckillCouponClaimFailureCode.RETRY_EXHAUSTED, cause, attemptCount);
        log.error("[SECKILL_CLAIM_DLQ_ALERT] 领取消息已进入死信队列，messageId={}，claimId={}，attemptCount={}",
                messageExt.getMsgId(), decodeResult.getMessage().getClaimId(), attemptCount);
    }
}
