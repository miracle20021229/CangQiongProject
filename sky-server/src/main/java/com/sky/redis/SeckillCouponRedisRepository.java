package com.sky.redis;

import com.sky.entity.SeckillCoupon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀券 Redis 操作封装。
 * 负责保存Lua所需的活动快照并执行原子预扣，不处理业务流程和数据库事务。
 */
@Component
public class SeckillCouponRedisRepository {

    public static final Long SUCCESS = 0L;
    public static final Long OUT_OF_STOCK = 1L;
    public static final Long DUPLICATE_CLAIM = 2L;
    public static final Long ACTIVITY_NOT_INITIALIZED = 3L;
    public static final Long ACTIVITY_DISABLED = 4L;
    public static final Long ACTIVITY_NOT_STARTED = 5L;
    public static final Long ACTIVITY_ENDED = 6L;
    private static final String KEY_PREFIX = "seckill:coupon:{";
    private static final String STATUS_FIELD = "status";
    private static final String START_TIME_FIELD = "startTime";
    private static final String END_TIME_FIELD = "endTime";
    private static final String STOCK_FIELD = "stock";
    private static final String CLEANUP_TIME_FIELD = "cleanupTime";
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀券 Lua 脚本对象，由 Spring 容器根据 Bean 名称注入。
     */
    @Autowired
    @Qualifier("seckillCouponLuaScript")
    private DefaultRedisScript<Long> seckillCouponLuaScript;

    /**
     * 将秒杀需要的券快照写入Redis Hash。
     * 只保存Lua会使用的状态、领取时间和库存；展示字段仍由逻辑过期列表缓存负责。
     */
    public void syncActivity(SeckillCoupon coupon) {
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

        String activityKey = activityKey(coupon.getId());
        stringRedisTemplate.opsForHash().putAll(activityKey, activity);
        Date cleanupDate = Date.from(coupon.getClaimEndTime().plusDays(1L).atZone(BUSINESS_ZONE_ID).toInstant());
        stringRedisTemplate.expireAt(activityKey, cleanupDate);
        stringRedisTemplate.expireAt(claimedUsersKey(coupon.getId()), cleanupDate);
    }

    /**
     * 执行Lua，原子校验活动、时间、库存和一人一券，并预扣一张Redis库存。
     */
    public Long tryPreDeduct(Long couponId, Long userId) {
        return stringRedisTemplate.execute(seckillCouponLuaScript, Arrays.asList(activityKey(couponId), claimedUsersKey(couponId)), String.valueOf(userId));
    }

    private String activityKey(Long couponId) {
        return KEY_PREFIX + couponId + "}:activity";
    }

    private String claimedUsersKey(Long couponId) {
        return KEY_PREFIX + couponId + "}:users";
    }
}
