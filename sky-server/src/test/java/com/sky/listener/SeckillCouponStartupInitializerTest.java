package com.sky.listener;

import com.sky.entity.SeckillCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mq.producer.SeckillCouponCompensationProducer;
import com.sky.service.SeckillCouponCacheSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponStartupInitializerTest {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private SeckillCouponCacheSyncService cacheSyncService;
    @Mock
    private SeckillCouponCompensationProducer compensationProducer;

    private SeckillCouponStartupInitializer startupInitializer;

    @BeforeEach
    void setUp() {
        startupInitializer = new SeckillCouponStartupInitializer(
                seckillCouponMapper,
                cacheSyncService,
                compensationProducer);
    }

    /**
     * 单张券恢复及其补偿消息均失败时，继续恢复后续秒杀券。
     */
    @Test
    void shouldContinueRestoringCouponsWhenCompensationMessageFails() {
        when(seckillCouponMapper.listEnabledNotEnded(any()))
                .thenReturn(Arrays.asList(
                        SeckillCoupon.builder().id(61L).build(),
                        SeckillCoupon.builder().id(62L).build()));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cacheSyncService).synchronizeCouponActivity(61L);
        when(compensationProducer.trySendActivitySnapshotSync(
                61L, "应用启动恢复Redis活动失败")).thenReturn(false);

        startupInitializer.initializeAfterStartup();

        verify(cacheSyncService).warmUpAvailableCouponCache();
        verify(cacheSyncService).synchronizeCouponActivity(61L);
        verify(cacheSyncService).synchronizeCouponActivity(62L);
    }
}
