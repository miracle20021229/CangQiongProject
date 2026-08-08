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
    void shouldRequeryMysqlBeforeSynchronizingActivity() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(31L)
                .status(1)
                .build();
        when(seckillCouponMapper.getById(31L)).thenReturn(coupon);

        cacheSyncService.synchronizeCouponActivity(31L);

        verify(seckillCouponRedisRepository).syncActivity(coupon);
    }

    @Test
    void shouldFailForMissingCouponSoRocketMqCanRetry() {
        when(seckillCouponMapper.getById(32L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> cacheSyncService.synchronizeCouponActivity(32L));
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
