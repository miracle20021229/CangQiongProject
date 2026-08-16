package com.sky.seckill.mq.consumer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import com.sky.seckill.service.SeckillCouponClaimFailureService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 流程5失败治理消费者。
 * 将隔离、待对账和死信事实异步落库，不让失败记录额外占用主领取消费链的 MySQL 连接。
 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${sky.rocketmq.seckill-coupon-claim.failure-topic:sky-seckill-coupon-claim-failure}",
        consumerGroup = "${sky.rocketmq.seckill-coupon-claim.failure-consumer-group:sky-seckill-coupon-claim-failure-consumer}",
        selectorExpression = SeckillCouponClaimConstant.FAILURE_TAG,
        consumeThreadNumber = 1,
        consumeThreadMax = 4,
        maxReconsumeTimes = SeckillCouponClaimConstant.MAX_RECONSUME_TIMES
)
public class SeckillCouponClaimFailureConsumer implements RocketMQListener<SeckillCouponClaimFailureMessage> {

    // 负责校验并幂等保存最终失败事实的领域服务。
    private final SeckillCouponClaimFailureService failureService;

    /**
     * 注入失败治理事实持久化服务。
     *
     * @param failureService 失败治理事实持久化服务
     */
    public SeckillCouponClaimFailureConsumer(SeckillCouponClaimFailureService failureService) {
        this.failureService = failureService;
    }

    /**
     * 校验治理消息版本与必填字段，并把最终失败事实幂等写入MySQL。
     *
     * @param message 失败治理消息
     */
    @Override
    public void onMessage(SeckillCouponClaimFailureMessage message) {
        if (message == null || !message.hasRequiredFields()
                || !Integer.valueOf(SeckillCouponClaimConstant.FAILURE_MESSAGE_SCHEMA_VERSION)
                .equals(message.getSchemaVersion())) {
            throw new IllegalArgumentException("秒杀券领取失败治理消息不合法");
        }

        failureService.persist(message);
        log.info("秒杀券领取失败治理消息已落库，failureId={}，action={}，errorCode={}",
                message.getFailureId(), message.getAction(), message.getErrorCode());
    }
}
