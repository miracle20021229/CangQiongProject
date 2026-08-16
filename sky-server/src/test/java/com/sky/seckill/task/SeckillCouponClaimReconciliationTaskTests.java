package com.sky.seckill.task;

import com.sky.seckill.service.SeckillCouponClaimReconciliationService;
import com.sky.seckill.service.SeckillCouponClaimRepairService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证流程6A/B定时任务只编排服务调用，不承载业务判断。
 */
class SeckillCouponClaimReconciliationTaskTests {

    /**
     * 调度入口只负责触发服务，不承载任何对账业务判断。
     */
    @Test
    void shouldDelegateScheduledRunToReconciliationService() {
        SeckillCouponClaimReconciliationService reconciliationService =
                mock(SeckillCouponClaimReconciliationService.class);
        SeckillCouponClaimRepairService repairService =
                mock(SeckillCouponClaimRepairService.class);
        when(reconciliationService.reconcileBatch()).thenReturn(2);
        when(repairService.repairBatch()).thenReturn(1);
        SeckillCouponClaimReconciliationTask task =
                new SeckillCouponClaimReconciliationTask(reconciliationService, repairService);

        task.reconcilePendingClaims();

        verify(reconciliationService).reconcileBatch();
        verify(repairService).repairBatch();
    }
}
