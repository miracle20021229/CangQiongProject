package com.sky.listener;

import com.sky.event.SeckillCouponChangedEvent;
import com.sky.mq.producer.SeckillCouponCompensationProducer;
import com.sky.service.SeckillCouponCacheSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponCacheListenerTest {

    @Mock
    private SeckillCouponCacheSyncService cacheSyncService;
    @Mock
    private SeckillCouponCompensationProducer compensationProducer;

    private SeckillCouponCacheListener cacheListener;

    @BeforeEach
    void setUp() {
        cacheListener = new SeckillCouponCacheListener(
                cacheSyncService, compensationProducer);
    }

    @Test
    void shouldOnlyRebuildListForCreatedCoupon() {
        when(cacheSyncService.rebuildAvailableCouponCache()).thenReturn(2);

        cacheListener.onCouponChanged(new SeckillCouponChangedEvent(
                51L, SeckillCouponChangedEvent.ChangeType.CREATED));

        verify(cacheSyncService).rebuildAvailableCouponCache();
        verify(cacheSyncService, never()).synchronizeCouponActivity(51L);
    }

    @Test
    void shouldCompensateEachFailedProjectionIndependently() {
        when(cacheSyncService.rebuildAvailableCouponCache())
                .thenThrow(new IllegalStateException("list redis unavailable"));
        doThrow(new IllegalStateException("activity redis unavailable"))
                .when(cacheSyncService).synchronizeCouponActivity(52L);

        cacheListener.onCouponChanged(new SeckillCouponChangedEvent(
                52L, SeckillCouponChangedEvent.ChangeType.STATUS_CHANGED));

        verify(compensationProducer).trySendAvailableCacheRebuild(
                "秒杀券状态事务提交");
        verify(compensationProducer).trySendActivitySnapshotSync(
                52L, "秒杀券状态事务提交");
    }

    /**
     * 列表补偿消息发送失败后，仍继续同步独立的Redis活动快照。
     */
    @Test
    void shouldContinueActivitySyncWhenListCompensationMessageFails() {
        when(cacheSyncService.rebuildAvailableCouponCache())
                .thenThrow(new IllegalStateException("list redis unavailable"));
        when(compensationProducer.trySendAvailableCacheRebuild(
                "秒杀券状态事务提交")).thenReturn(false);

        cacheListener.onCouponChanged(new SeckillCouponChangedEvent(
                53L, SeckillCouponChangedEvent.ChangeType.STATUS_CHANGED));

        verify(cacheSyncService).synchronizeCouponActivity(53L);
    }

    @Test
    void shouldRepairActivityWithoutOverwritingCompleteSnapshot() {
        when(cacheSyncService.rebuildAvailableCouponCache()).thenReturn(1);

        cacheListener.onCouponChanged(new SeckillCouponChangedEvent(
                54L, SeckillCouponChangedEvent.ChangeType.ACTIVITY_REPAIR_REQUESTED));

        verify(cacheSyncService).repairCouponActivity(54L);
        verify(cacheSyncService, never()).synchronizeCouponActivity(54L);
    }

    @Test
    void shouldSendRepairCompensationWhenActivityRepairFails() {
        when(cacheSyncService.rebuildAvailableCouponCache()).thenReturn(1);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cacheSyncService).repairCouponActivity(55L);

        cacheListener.onCouponChanged(new SeckillCouponChangedEvent(
                55L, SeckillCouponChangedEvent.ChangeType.ACTIVITY_REPAIR_REQUESTED));

        verify(compensationProducer).trySendActivitySnapshotRepair(
                55L, "重复启停请求修复Redis活动");
    }
}
