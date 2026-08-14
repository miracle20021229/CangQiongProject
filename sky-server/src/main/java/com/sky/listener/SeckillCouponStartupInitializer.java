package com.sky.listener;

import com.sky.entity.SeckillCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mq.producer.SeckillCouponCompensationProducer;
import com.sky.service.SeckillCouponCacheSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀券缓存启动初始化器。
 */
@Component
@Slf4j
public class SeckillCouponStartupInitializer {

    private final SeckillCouponMapper seckillCouponMapper;
    private final SeckillCouponCacheSyncService seckillCouponCacheSyncService;
    private final SeckillCouponCompensationProducer compensationProducer;

    public SeckillCouponStartupInitializer(SeckillCouponMapper seckillCouponMapper, SeckillCouponCacheSyncService seckillCouponCacheSyncService, SeckillCouponCompensationProducer compensationProducer) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.seckillCouponCacheSyncService = seckillCouponCacheSyncService;
        this.compensationProducer = compensationProducer;
    }

    /**
     * 应用启动后预热列表缓存，并恢复未结束的Redis秒杀活动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        // 数据库查询失败时保持启动失败，避免在表结构缺失时带病运行。
        seckillCouponCacheSyncService.warmUpAvailableCouponCache();
        List<SeckillCoupon> enabledCoupons =
                seckillCouponMapper.listEnabledNotEnded(LocalDateTime.now());

        int restoredCount = 0;
        for (SeckillCoupon coupon : enabledCoupons) {
            try {
                if (seckillCouponCacheSyncService.repairCouponActivity(coupon.getId())) {
                    restoredCount++;
                }
            } catch (RuntimeException exception) {
                log.error("Redis秒杀活动恢复失败，couponId={}", coupon.getId(), exception);
                compensationProducer.trySendActivitySnapshotRepair(
                        coupon.getId(), "应用启动恢复Redis活动失败");
            }
        }
        log.info("秒杀券Redis启动恢复完成，恢复活动数量={}", restoredCount);
    }
}
