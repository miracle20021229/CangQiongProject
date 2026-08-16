package com.sky.seckill.task;

import com.sky.seckill.service.SeckillCouponClaimReconciliationService;
import com.sky.seckill.service.SeckillCouponClaimRepairService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 流程6内部调度入口，不对管理端或用户端暴露失败治理接口。
 */
@Component
@ConditionalOnProperty(prefix = "sky.seckill-coupon.claim-reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SeckillCouponClaimReconciliationTask {

    // 执行流程6A单笔证据识别的服务。
    private final SeckillCouponClaimReconciliationService reconciliationService;
    // 执行流程6B受控正向修复的服务。
    private final SeckillCouponClaimRepairService repairService;

    /**
     * 注入流程6对账服务，调度层只负责触发而不承载业务判定。
     */
    public SeckillCouponClaimReconciliationTask(SeckillCouponClaimReconciliationService reconciliationService, SeckillCouponClaimRepairService repairService) {
        this.reconciliationService = reconciliationService;
        this.repairService = repairService;
    }

    /**
     * 按配置周期扫描最终失败记录，批次内每条记录独立完成对账。
     */
    @Scheduled(cron = "${sky.seckill-coupon.claim-reconciliation.cron:0/30 * * * * ?}")
    public void reconcilePendingClaims() {
        int reconciledCount = reconciliationService.reconcileBatch();
        int repairedCount = repairService.repairBatch();
        if (reconciledCount > 0 || repairedCount > 0) {
            log.info("流程6A/B批次结束，识别记录数={}，修复记录数={}", reconciledCount, repairedCount);
        }
    }
}
