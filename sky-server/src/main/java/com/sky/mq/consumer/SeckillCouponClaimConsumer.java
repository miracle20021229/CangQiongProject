package com.sky.mq.consumer;

import com.sky.constant.SeckillCouponClaimConstant;
import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.service.impl.SeckillCouponClaimPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 流程4-步骤13～14：异步接收Broker已提交的领取消息，并调用独立MySQL事务持久化。
 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${sky.rocketmq.seckill-coupon-claim.topic:sky-seckill-coupon-claim}",
        consumerGroup = "${sky.rocketmq.seckill-coupon-claim.consumer-group:sky-seckill-coupon-claim-consumer}",
        selectorExpression = SeckillCouponClaimConstant.CLAIM_TAG,
        consumeThreadNumber = 4,
        consumeThreadMax = 16,
        maxReconsumeTimes = 5
)
public class SeckillCouponClaimConsumer implements RocketMQListener<SeckillCouponClaimMessage> {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private final SeckillCouponClaimPersistenceService persistenceService;

    public SeckillCouponClaimConsumer(SeckillCouponClaimPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    /**
     * 流程4-步骤13：由RocketMQ消费线程异步调用，原HTTP请求不会等待本方法完成。
     */
    @Override
    public void onMessage(SeckillCouponClaimMessage message) {
        // TODO 流程5：引入领取持久化错误码，区分可重试故障与永久业务错误；流程6再按失败记录和错误码执行对账修复。
        if (message == null || !message.hasRequiredFields() || !Integer.valueOf(SUPPORTED_SCHEMA_VERSION).equals(message.getSchemaVersion())) {
            throw new IllegalArgumentException("秒杀券领取消息数据不完整或版本不受支持");
        }

        log.info("开始持久化秒杀券领取消息，claimId={}，couponId={}，userId={}", message.getClaimId(), message.getCouponId(), message.getUserId());
        // 流程4-步骤14：调用独立持久化Service，事务边界从persist()进入。
        persistenceService.persist(message);
        log.info("秒杀券领取消息持久化完成，claimId={}", message.getClaimId());
    }
}
