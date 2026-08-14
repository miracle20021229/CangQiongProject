package com.sky.redis;

import com.sky.entity.SeckillCoupon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponRedisRepositoryTests {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private DefaultRedisScript<Long> seckillCouponLuaScript;

    private SeckillCouponRedisRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SeckillCouponRedisRepository(stringRedisTemplate, seckillCouponLuaScript);
    }

    @Test
    void shouldInitializeActivityWithCompleteHashTagKeys() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(71L)
                .status(1)
                .remainingStock(20)
                .claimStartTime(LocalDateTime.now().minusMinutes(1))
                .claimEndTime(LocalDateTime.now().plusHours(1))
                .build();

        repository.initializeActivity(coupon);

        verify(hashOperations).putAll(eq("seckill:coupon:{71}:activity"), anyMap());
        verify(stringRedisTemplate).expireAt(eq("seckill:coupon:{71}:activity"), any(Instant.class));
        verify(stringRedisTemplate).expireAt(eq("seckill:coupon:{71}:users"), any(Instant.class));
        verify(stringRedisTemplate).expireAt(eq("seckill:coupon:{71}:claims"), any(Instant.class));
    }

    @Test
    void shouldOnlyUpdateExistingActivityStatus() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.hasKey("seckill:coupon:{72}:activity")).thenReturn(true);

        repository.updateActivityStatus(72L, 0);

        verify(hashOperations).put("seckill:coupon:{72}:activity", "status", "0");
    }

    @Test
    void shouldDetectCompleteAndIncompleteActivities() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(eq("seckill:coupon:{73}:activity"), anyCollection()))
                .thenReturn(List.of("1", "100", "200", "10", "300"));
        when(hashOperations.multiGet(eq("seckill:coupon:{74}:activity"), anyCollection()))
                .thenReturn(Arrays.asList("1", "100", null, "10", "300"));

        assertTrue(repository.isActivityComplete(73L));
        assertFalse(repository.isActivityComplete(74L));
    }

    @Test
    void shouldExecuteLuaWithTwoKeysInTheSameRedisSlot() {
        when(stringRedisTemplate.execute(eq(seckillCouponLuaScript), anyList(), eq("81"), eq("claim-75"))).thenReturn(0L);

        Long result = repository.tryPreDeduct(75L, 81L, "claim-75");

        assertEquals(0L, result);
        verify(stringRedisTemplate).execute(seckillCouponLuaScript, List.of("seckill:coupon:{75}:activity", "seckill:coupon:{75}:users", "seckill:coupon:{75}:claims"), "81", "claim-75");
    }

    @Test
    void shouldCheckClaimTransactionMarker() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("seckill:coupon:{76}:claims", "claim-76")).thenReturn("82");

        assertTrue(repository.isClaimPreDeducted(76L, 82L, "claim-76"));
    }
}
