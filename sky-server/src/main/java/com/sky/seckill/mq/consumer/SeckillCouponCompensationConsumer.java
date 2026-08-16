package com.sky.seckill.mq.consumer;

import com.sky.seckill.mq.message.SeckillCouponCompensationMessage;
import com.sky.seckill.mq.message.SeckillCouponCompensationMessage.CompensationType;
import com.sky.seckill.service.SeckillCouponCacheSyncService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流程2秒杀券Redis补偿消息消费者。
 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${sky.rocketmq.seckill-coupon-compensation.topic:sky-seckill-coupon-compensation}",
        consumerGroup = "${sky.rocketmq.seckill-coupon-compensation.consumer-group:sky-seckill-coupon-compensation-consumer}",
        selectorExpression = "AVAILABLE_CACHE_REBUILD || ACTIVITY_SNAPSHOT_SYNC || ACTIVITY_SNAPSHOT_REPAIR",
        consumeThreadNumber = 2,
        consumeThreadMax = 8,
        maxReconsumeTimes = 5
)
public class SeckillCouponCompensationConsumer implements RocketMQListener<SeckillCouponCompensationMessage> {

    // 当前消费者支持的补偿消息结构版本。
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    // 按MySQL事实重建对应Redis缓存投影的应用服务。
    private final SeckillCouponCacheSyncService cacheSyncService;

    /**
     * 创建秒杀券缓存补偿消息消费者。
     *
     * @param cacheSyncService 秒杀券缓存同步服务
     */
    public SeckillCouponCompensationConsumer(SeckillCouponCacheSyncService cacheSyncService) {
        this.cacheSyncService = cacheSyncService;
    }

    /**
     * 校验并分发补偿消息，执行失败时向上抛出异常触发Broker重试。
     */
    @Override
    public void onMessage(SeckillCouponCompensationMessage message) {
        validateMessage(message);
        log.info("开始消费RocketMQ补偿消息，eventId={}，type={}，couponId={}，reason={}",
                message.getEventId(), message.getType(), message.getCouponId(), message.getReason());

        try {
            if (CompensationType.AVAILABLE_CACHE_REBUILD.equals(message.getType())) {
                cacheSyncService.rebuildAvailableCouponCache();
            } else if (CompensationType.ACTIVITY_SNAPSHOT_SYNC.equals(message.getType())) {
                cacheSyncService.synchronizeCouponActivity(message.getCouponId());
            } else if (CompensationType.ACTIVITY_SNAPSHOT_REPAIR.equals(message.getType())) {
                cacheSyncService.repairCouponActivity(message.getCouponId());
            } else {
                throw new IllegalArgumentException("不支持的秒杀券补偿类型：" + message.getType());
            }
        } catch (RuntimeException exception) {
            log.error("RocketMQ补偿消息消费失败，将由Broker重试，eventId={}，type={}，couponId={}",
                    message.getEventId(), message.getType(), message.getCouponId(), exception);
            throw exception;
        }

        log.info("RocketMQ补偿消息消费完成，eventId={}，type={}，couponId={}",
                message.getEventId(), message.getType(), message.getCouponId());
    }

    /**
     * 校验消息标识、结构版本、补偿类型和必要的业务参数。
     */
    private void validateMessage(SeckillCouponCompensationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("秒杀券补偿消息不能为空");
        }
        if (!StringUtils.hasText(message.getEventId())) {
            throw new IllegalArgumentException("秒杀券补偿消息eventId不能为空");
        }
        if (!Integer.valueOf(SUPPORTED_SCHEMA_VERSION).equals(message.getSchemaVersion())) {
            throw new IllegalArgumentException("不支持的秒杀券补偿消息版本：" + message.getSchemaVersion());
        }
        if (message.getType() == null) {
            throw new IllegalArgumentException("秒杀券补偿消息type不能为空");
        }
        if ((CompensationType.ACTIVITY_SNAPSHOT_SYNC.equals(message.getType())
                || CompensationType.ACTIVITY_SNAPSHOT_REPAIR.equals(message.getType()))
                && message.getCouponId() == null) {
            throw new IllegalArgumentException("活动快照补偿消息couponId不能为空");
        }
    }
}
