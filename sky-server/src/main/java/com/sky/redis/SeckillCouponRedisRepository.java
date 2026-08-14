package com.sky.redis;

import com.sky.entity.SeckillCoupon;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀券 Redis 操作封装。
 * 负责保存Lua所需的活动快照并执行原子预扣，不处理业务流程和数据库事务。
 */
@Component
public class SeckillCouponRedisRepository {

    private static final String ACTIVITY_KEY_TEMPLATE = "seckill:coupon:{%s}:activity";
    private static final String CLAIMED_USERS_KEY_TEMPLATE = "seckill:coupon:{%s}:users";
    private static final String CLAIM_TRANSACTIONS_KEY_TEMPLATE = "seckill:coupon:{%s}:claims";
    private static final String STATUS_FIELD = "status";
    private static final String START_TIME_FIELD = "startTime";
    private static final String END_TIME_FIELD = "endTime";
    private static final String STOCK_FIELD = "stock";
    private static final String CLEANUP_TIME_FIELD = "cleanupTime";
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> seckillCouponLuaScript;

    public SeckillCouponRedisRepository(StringRedisTemplate stringRedisTemplate, @Qualifier("seckillCouponLuaScript") DefaultRedisScript<Long> seckillCouponLuaScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillCouponLuaScript = seckillCouponLuaScript;
    }

    /**
     * 完整初始化秒杀活动快照。
     * 仅在活动启用或快照缺失时调用，避免覆盖Lua已经预扣的Redis库存。
     */
    public void initializeActivity(SeckillCoupon coupon) {
        if (coupon == null || coupon.getId() == null || coupon.getStatus() == null || coupon.getRemainingStock() == null
                || coupon.getClaimStartTime() == null || coupon.getClaimEndTime() == null) {
            throw new IllegalArgumentException("秒杀券活动数据不完整，无法同步到Redis");
        }

        long startTime = coupon.getClaimStartTime().atZone(BUSINESS_ZONE_ID).toEpochSecond();
        long endTime = coupon.getClaimEndTime().atZone(BUSINESS_ZONE_ID).toEpochSecond();
        long cleanupTime = coupon.getClaimEndTime().plusDays(1L).atZone(BUSINESS_ZONE_ID).toEpochSecond();
        Map<String, String> activity = new HashMap<>();
        activity.put(STATUS_FIELD, String.valueOf(coupon.getStatus()));
        activity.put(START_TIME_FIELD, String.valueOf(startTime));
        activity.put(END_TIME_FIELD, String.valueOf(endTime));
        activity.put(STOCK_FIELD, String.valueOf(coupon.getRemainingStock()));
        activity.put(CLEANUP_TIME_FIELD, String.valueOf(cleanupTime));

        String activityKey = ACTIVITY_KEY_TEMPLATE.formatted(coupon.getId());
        String claimedUsersKey = CLAIMED_USERS_KEY_TEMPLATE.formatted(coupon.getId());
        String claimTransactionsKey = CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(coupon.getId());
        stringRedisTemplate.opsForHash().putAll(activityKey, activity);
        Instant cleanupInstant = Instant.ofEpochSecond(cleanupTime);
        stringRedisTemplate.expireAt(activityKey, cleanupInstant);
        stringRedisTemplate.expireAt(claimedUsersKey, cleanupInstant);
        stringRedisTemplate.expireAt(claimTransactionsKey, cleanupInstant);
    }

    /**
     * 停用活动时只更新状态，保留Redis库存和已领取用户集合。
     */
    public void updateActivityStatus(Long couponId, Integer status) {
        if (couponId == null || status == null) {
            throw new IllegalArgumentException("优惠券ID和状态不能为空");
        }

        String activityKey = ACTIVITY_KEY_TEMPLATE.formatted(couponId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(activityKey))) {
            stringRedisTemplate.opsForHash().put(activityKey, STATUS_FIELD, String.valueOf(status));
        }
    }

    /**
     * 判断Lua所需的活动字段是否完整。
     */
    public boolean isActivityComplete(Long couponId) {
        if (couponId == null) {
            throw new IllegalArgumentException("优惠券ID不能为空");
        }

        String activityKey = ACTIVITY_KEY_TEMPLATE.formatted(couponId);
        List<Object> fields = stringRedisTemplate.opsForHash().multiGet(activityKey, List.of(STATUS_FIELD, START_TIME_FIELD, END_TIME_FIELD, STOCK_FIELD, CLEANUP_TIME_FIELD));
        return fields != null && fields.size() == 5 && fields.stream().noneMatch(field -> field == null || String.valueOf(field).isEmpty());
    }

    /**
     * 流程3-步骤9～10：接收Listener的预扣请求，并执行Lua原子校验与库存预扣。
     */
    public Long tryPreDeduct(Long couponId, Long userId, String claimId) {
        if (couponId == null || userId == null || claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("优惠券ID、用户ID和领取流水ID不能为空");
        }
        // 流程3-步骤10：Redis在一次Lua执行中完成校验、扣库存、记录用户和claimId，并返回0～6。
        return stringRedisTemplate.execute(seckillCouponLuaScript, List.of(ACTIVITY_KEY_TEMPLATE.formatted(couponId), CLAIMED_USERS_KEY_TEMPLATE.formatted(couponId), CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(couponId)), String.valueOf(userId), claimId);
    }

    /**
     * RocketMQ回查事务状态时确认指定领取流水是否已完成Redis预扣。
     */
    public boolean isClaimPreDeducted(Long couponId, Long userId, String claimId) {
        if (couponId == null || userId == null || claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("优惠券ID、用户ID和领取流水ID不能为空");
        }
        Object reservedUserId = stringRedisTemplate.opsForHash().get(CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(couponId), claimId);
        return String.valueOf(userId).equals(reservedUserId);
    }
}
