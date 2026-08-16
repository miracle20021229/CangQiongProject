package com.sky.seckill.listener;

import com.sky.entity.SeckillCoupon;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mq.producer.SeckillCouponCompensationProducer;
import com.sky.seckill.service.SeckillCouponCacheSyncService;
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

    // 查询启动时仍有效秒杀活动的数据访问接口。
    private final SeckillCouponMapper seckillCouponMapper;
    // 预热列表并恢复秒杀活动快照的应用服务。
    private final SeckillCouponCacheSyncService seckillCouponCacheSyncService;
    // 单项快照恢复失败时发送补偿任务的生产者。
    private final SeckillCouponCompensationProducer compensationProducer;

    /**
     * 创建秒杀券启动初始化器。
     *
     * @param seckillCouponMapper 秒杀券数据访问接口
     * @param seckillCouponCacheSyncService 秒杀券缓存同步服务
     * @param compensationProducer 缓存补偿消息生产者
     */
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
