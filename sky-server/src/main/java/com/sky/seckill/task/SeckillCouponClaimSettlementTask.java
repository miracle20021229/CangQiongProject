package com.sky.seckill.task;

import com.sky.seckill.service.SeckillCouponClaimSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 流程6C活动结束总账调度入口，与单笔领取修复分开控制执行频率。
 */
@Component
@ConditionalOnProperty(prefix = "sky.seckill-coupon.claim-reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SeckillCouponClaimSettlementTask {

    // 执行流程6C活动结束总账核对的服务。
    private final SeckillCouponClaimSettlementService settlementService;

    /**
     * 注入活动结算服务，调度层不承载库存判定逻辑。
     */
    public SeckillCouponClaimSettlementTask(SeckillCouponClaimSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 按独立配置周期发现结束活动并执行券维度总账核对。
     */
    @Scheduled(cron = "${sky.seckill-coupon.claim-reconciliation.settlement-cron:15 * * * * ?}")
    public void settleEndedActivities() {
        int processedCount = settlementService.settleBatch();
        if (processedCount > 0) {
            log.info("流程6C活动结算批次结束，处理活动数={}", processedCount);
        }
    }
}
