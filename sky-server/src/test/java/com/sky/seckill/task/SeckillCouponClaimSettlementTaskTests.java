package com.sky.seckill.task;

import com.sky.seckill.service.SeckillCouponClaimSettlementService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证流程6C定时任务只触发活动结算服务。
 */
class SeckillCouponClaimSettlementTaskTests {

    /**
     * 流程6C调度入口只负责触发结算服务。
     */
    @Test
    void shouldDelegateScheduledRunToSettlementService() {
        SeckillCouponClaimSettlementService settlementService =
                mock(SeckillCouponClaimSettlementService.class);
        when(settlementService.settleBatch()).thenReturn(1);
        SeckillCouponClaimSettlementTask task =
                new SeckillCouponClaimSettlementTask(settlementService);

        task.settleEndedActivities();

        verify(settlementService).settleBatch();
    }
}
