package com.sky.seckill.redis;

import com.sky.entity.SeckillCoupon;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.time.Duration;
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

/**
 * 验证秒杀Redis仓储的同槽位Key、原子预扣、对账证据读取和延迟清理行为。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponRedisRepositoryTests {

    // 模拟秒杀正确性状态专用Redis模板。
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    // 模拟Redis Hash操作接口。
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    // 模拟Redis Set操作接口。
    @Mock
    private SetOperations<String, String> setOperations;
    // 模拟秒杀券Lua原子预扣脚本。
    @Mock
    private DefaultRedisScript<Long> seckillCouponLuaScript;

    // 被测的秒杀券Redis仓储。
    private SeckillCouponRedisRepository repository;

    /**
     * 为每个用例创建使用默认证据保留参数的Redis仓储。
     */
    @BeforeEach
    void setUp() {
        SeckillCouponClaimReconciliationProperties properties =
                new SeckillCouponClaimReconciliationProperties();
        repository = new SeckillCouponRedisRepository(
                stringRedisTemplate, seckillCouponLuaScript, properties);
    }

    /**
     * 验证初始化活动时三类Redis Key共享couponId哈希标签并设置过期时间。
     */
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

    /**
     * 验证活动启停同步只更新已经存在的活动Hash。
     */
    @Test
    void shouldOnlyUpdateExistingActivityStatus() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.hasKey("seckill:coupon:{72}:activity")).thenReturn(true);

        repository.updateActivityStatus(72L, 0);

        verify(hashOperations).put("seckill:coupon:{72}:activity", "status", "0");
    }

    /**
     * 验证活动Hash只有包含Lua所需全部字段时才被判定为完整。
     */
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

    /**
     * 验证预扣Lua使用同一Redis Cluster槽位中的活动、用户和流水Key。
     */
    @Test
    void shouldExecuteLuaWithTwoKeysInTheSameRedisSlot() {
        when(stringRedisTemplate.execute(eq(seckillCouponLuaScript), anyList(), eq("81"), eq("claim-75"))).thenReturn(0L);

        Long result = repository.tryPreDeduct(75L, 81L, "claim-75");

        assertEquals(0L, result);
        verify(stringRedisTemplate).execute(seckillCouponLuaScript, List.of("seckill:coupon:{75}:activity", "seckill:coupon:{75}:users", "seckill:coupon:{75}:claims"), "81", "claim-75");
    }

    /**
     * 验证RocketMQ事务回查按claimId和userId确认Redis预扣标记。
     */
    @Test
    void shouldCheckClaimTransactionMarker() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("seckill:coupon:{76}:claims", "claim-76")).thenReturn("82");

        assertTrue(repository.isClaimPreDeducted(76L, 82L, "claim-76"));
    }

    /**
     * 验证流程6能够读取claimId在Redis中记录的领取用户。
     */
    @Test
    void shouldReadClaimOwnerForReconciliation() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("seckill:coupon:{77}:claims", "claim-77")).thenReturn("83");

        String claimOwner = repository.findClaimOwner(77L, "claim-77");

        assertEquals("83", claimOwner);
    }

    /**
     * 验证流程6能够读取用户是否存在于Redis已领取集合。
     */
    @Test
    void shouldReadClaimedUserEvidenceForReconciliation() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("seckill:coupon:{78}:users", "84")).thenReturn(true);

        assertTrue(repository.isUserClaimed(78L, 84L));
    }

    /**
     * 活动总账应通过一次Lua执行读取库存、users数量和claims数量。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldReadAtomicSettlementEvidence() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList()))
                .thenReturn(List.of("8", "2", "2"));

        SeckillCouponRedisRepository.SettlementEvidenceSnapshot snapshot =
                repository.readSettlementEvidence(79L);

        assertEquals(8L, snapshot.getRemainingStock());
        assertEquals(2L, snapshot.getUserCount());
        assertEquals(2L, snapshot.getClaimCount());
    }

    /**
     * 总账一致后应统一安排三类Redis证据延迟清理。
     */
    @Test
    void shouldScheduleAllEvidenceKeysForCleanup() {
        repository.scheduleEvidenceCleanup(80L, Duration.ofDays(1));

        verify(stringRedisTemplate).expireAt(
                eq("seckill:coupon:{80}:activity"), any(Instant.class));
        verify(stringRedisTemplate).expireAt(
                eq("seckill:coupon:{80}:users"), any(Instant.class));
        verify(stringRedisTemplate).expireAt(
                eq("seckill:coupon:{80}:claims"), any(Instant.class));
    }
}
