package com.sky.listener;

import com.sky.event.SeckillCouponChangedEvent;
import com.sky.mq.producer.SeckillCouponCompensationProducer;
import com.sky.service.SeckillCouponCacheSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 秒杀券数据变更后的缓存投影监听器。
 * 只在MySQL事务提交成功后执行；Redis失败时发送RocketMQ补偿消息。
 */
@Component
@Slf4j
public class SeckillCouponCacheListener {

    private final SeckillCouponCacheSyncService seckillCouponCacheSyncService;
    private final SeckillCouponCompensationProducer compensationProducer;

    public SeckillCouponCacheListener(SeckillCouponCacheSyncService seckillCouponCacheSyncService, SeckillCouponCompensationProducer compensationProducer) {
        this.seckillCouponCacheSyncService = seckillCouponCacheSyncService;
        this.compensationProducer = compensationProducer;
    }

    /**
     * 数据库事务提交成功后刷新对应的Redis投影。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponChanged(SeckillCouponChangedEvent event) {
        String reason = switch (event.changeType()) {
            case CREATED -> "新增秒杀券事务提交";
            case UPDATED -> "修改秒杀券事务提交";
            case STATUS_CHANGED -> "秒杀券状态事务提交";
            case ACTIVITY_REPAIR_REQUESTED -> "重复启停请求修复Redis活动";
        };
        rebuildAvailableCacheWithCompensation(event, reason);

        if (SeckillCouponChangedEvent.ChangeType.STATUS_CHANGED.equals(event.changeType())) {
            synchronizeActivityWithCompensation(event.couponId(), reason);
        } else if (SeckillCouponChangedEvent.ChangeType.ACTIVITY_REPAIR_REQUESTED.equals(event.changeType())) {
            repairActivityWithCompensation(event.couponId(), reason);
        }
    }

    /**
     * 重建可领取列表缓存；重建失败时发送RocketMQ补偿消息。
     */
    private void rebuildAvailableCacheWithCompensation(SeckillCouponChangedEvent event, String reason) {
        try {
            int couponCount = seckillCouponCacheSyncService.rebuildAvailableCouponCache();
            log.info("秒杀券变更后列表缓存刷新完成，couponId={}，type={}，数量={}", event.couponId(), event.changeType(), couponCount);
        } catch (RuntimeException exception) {
            log.error("秒杀券变更后列表缓存刷新失败，couponId={}，type={}", event.couponId(), event.changeType(), exception);
            compensationProducer.trySendAvailableCacheRebuild(reason);
        }
    }

    /**
     * 同步Redis活动快照；同步失败时发送RocketMQ补偿消息。
     */
    private void synchronizeActivityWithCompensation(Long couponId, String reason) {
        try {
            seckillCouponCacheSyncService.synchronizeCouponActivity(couponId);
        } catch (RuntimeException exception) {
            log.error("秒杀券变更后Redis活动同步失败，couponId={}", couponId, exception);
            compensationProducer.trySendActivitySnapshotSync(couponId, reason);
        }
    }

    /**
     * 仅在活动快照不完整时修复；失败时发送对应的RocketMQ补偿消息。
     */
    private void repairActivityWithCompensation(Long couponId, String reason) {
        try {
            seckillCouponCacheSyncService.repairCouponActivity(couponId);
        } catch (RuntimeException exception) {
            log.error("秒杀券Redis活动修复失败，couponId={}", couponId, exception);
            compensationProducer.trySendActivitySnapshotRepair(couponId, reason);
        }
    }
}
