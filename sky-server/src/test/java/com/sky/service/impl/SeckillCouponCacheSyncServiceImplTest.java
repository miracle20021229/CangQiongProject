package com.sky.service.impl;

import com.sky.entity.SeckillCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.redis.SeckillCouponRedisRepository;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponCacheSyncServiceImplTest {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private CacheClient cacheClient;
    @Mock
    private SeckillCouponRedisRepository seckillCouponRedisRepository;

    private SeckillCouponCacheSyncServiceImpl cacheSyncService;

    @BeforeEach
    void setUp() {
        cacheSyncService = new SeckillCouponCacheSyncServiceImpl(
                seckillCouponMapper,
                cacheClient,
                seckillCouponRedisRepository);
    }

    @Test
    void shouldInitializeEnabledActivityFromMysql() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(31L)
                .status(1)
                .build();
        when(seckillCouponMapper.getById(31L)).thenReturn(coupon);

        cacheSyncService.synchronizeCouponActivity(31L);

        verify(seckillCouponRedisRepository).initializeActivity(coupon);
    }

    @Test
    void shouldOnlyUpdateStatusWhenActivityIsDisabled() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(32L)
                .status(0)
                .build();
        when(seckillCouponMapper.getById(32L)).thenReturn(coupon);

        cacheSyncService.synchronizeCouponActivity(32L);

        verify(seckillCouponRedisRepository).updateActivityStatus(32L, 0);
    }

    @Test
    void shouldKeepCompleteActivitySnapshotDuringRepair() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(33L)
                .status(1)
                .build();
        when(seckillCouponMapper.getById(33L)).thenReturn(coupon);
        when(seckillCouponRedisRepository.isActivityComplete(33L)).thenReturn(true);

        boolean repaired = cacheSyncService.repairCouponActivity(33L);

        assertEquals(false, repaired);
        verify(seckillCouponRedisRepository, org.mockito.Mockito.never()).initializeActivity(coupon);
    }

    @Test
    void shouldInitializeIncompleteActivityDuringRepair() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(34L)
                .status(1)
                .build();
        when(seckillCouponMapper.getById(34L)).thenReturn(coupon);
        when(seckillCouponRedisRepository.isActivityComplete(34L)).thenReturn(false);

        boolean repaired = cacheSyncService.repairCouponActivity(34L);

        assertEquals(true, repaired);
        verify(seckillCouponRedisRepository).initializeActivity(coupon);
    }

    @Test
    void shouldFailForMissingCouponSoRocketMqCanRetry() {
        when(seckillCouponMapper.getById(35L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> cacheSyncService.synchronizeCouponActivity(35L));
    }

    @Test
    void shouldRebuildAvailableCacheFromMysql() {
        List<SeckillCouponVO> coupons = Collections.singletonList(
                new SeckillCouponVO());
        when(seckillCouponMapper.listAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(coupons);

        int couponCount = cacheSyncService.rebuildAvailableCouponCache();

        assertEquals(1, couponCount);
        verify(cacheClient).setWithLogicalExpire(
                "cache:seckill:coupon:available",
                coupons,
                30L,
                TimeUnit.SECONDS);
    }
}
