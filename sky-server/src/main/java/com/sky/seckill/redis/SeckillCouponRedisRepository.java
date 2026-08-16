package com.sky.seckill.redis;

import com.sky.entity.SeckillCoupon;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.governance.support.GovernanceTiming;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    // 秒杀活动状态、时间和库存Hash的Key模板。
    private static final String ACTIVITY_KEY_TEMPLATE = "seckill:coupon:{%s}:activity";
    // 已领取用户Set的Key模板。
    private static final String CLAIMED_USERS_KEY_TEMPLATE = "seckill:coupon:{%s}:users";
    // claimId到userId领取流水Hash的Key模板。
    private static final String CLAIM_TRANSACTIONS_KEY_TEMPLATE = "seckill:coupon:{%s}:claims";
    // 活动Hash中的启停状态字段。
    private static final String STATUS_FIELD = "status";
    // 活动Hash中的开始时间字段。
    private static final String START_TIME_FIELD = "startTime";
    // 活动Hash中的结束时间字段。
    private static final String END_TIME_FIELD = "endTime";
    // 活动Hash中的剩余库存字段。
    private static final String STOCK_FIELD = "stock";
    // 活动Hash中的证据清理时间字段。
    private static final String CLEANUP_TIME_FIELD = "cleanupTime";
    // Lua原子读取时代表活动库存字段缺失的哨兵值。
    private static final String MISSING_VALUE = "__MISSING__";
    // 活动时间转换为Epoch秒时使用的统一业务时区。
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");
    // 原子读取库存、用户数和领取流水数的只读Lua脚本。
    private static final DefaultRedisScript<List> SETTLEMENT_EVIDENCE_SCRIPT = buildSettlementEvidenceScript();
    // 仅连接秒杀正确性状态Redis的字符串模板。
    private final StringRedisTemplate stringRedisTemplate;
    // 完成资格校验、库存预扣和领取证据写入的Lua脚本。
    private final DefaultRedisScript<Long> seckillCouponLuaScript;
    // 控制Redis领取证据默认保留时长的流程6参数。
    private final SeckillCouponClaimReconciliationProperties reconciliationProperties;

    /**
     * 注入秒杀专用Redis连接、预扣Lua脚本和流程6证据保留参数。
     */
    public SeckillCouponRedisRepository(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            @Qualifier("seckillCouponLuaScript") DefaultRedisScript<Long> seckillCouponLuaScript,
            SeckillCouponClaimReconciliationProperties reconciliationProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillCouponLuaScript = seckillCouponLuaScript;
        this.reconciliationProperties = reconciliationProperties;
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
        Duration evidenceRetention = GovernanceTiming.positiveOrDefault(
                reconciliationProperties.getEvidenceRetention(), Duration.ofDays(7));
        Instant cleanupInstant = coupon.getClaimEndTime()
                .atZone(BUSINESS_ZONE_ID)
                .toInstant()
                .plus(evidenceRetention);
        long cleanupTime = cleanupInstant.getEpochSecond();
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

    /**
     * 查询指定claimId在Redis预扣流水中记录的用户，用于流程6核对领取归属。
     */
    public String findClaimOwner(Long couponId, String claimId) {
        if (couponId == null || claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("优惠券ID和领取流水ID不能为空");
        }

        Object reservedUserId = stringRedisTemplate.opsForHash()
                .get(CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(couponId), claimId);
        return reservedUserId == null ? null : String.valueOf(reservedUserId);
    }

    /**
     * 查询用户是否存在于Redis已领取集合，用于流程6交叉验证claim流水。
     */
    public boolean isUserClaimed(Long couponId, Long userId) {
        if (couponId == null || userId == null) {
            throw new IllegalArgumentException("优惠券ID和用户ID不能为空");
        }

        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(CLAIMED_USERS_KEY_TEMPLATE.formatted(couponId), String.valueOf(userId)));
    }

    /**
     * 通过同槽位Lua原子读取活动库存、已领取用户数和claim流水数，供流程6C结算。
     */
    public SettlementEvidenceSnapshot readSettlementEvidence(Long couponId) {
        if (couponId == null) {
            throw new IllegalArgumentException("优惠券ID不能为空");
        }

        List<?> rawValues = stringRedisTemplate.execute(
                SETTLEMENT_EVIDENCE_SCRIPT,
                List.of(
                        ACTIVITY_KEY_TEMPLATE.formatted(couponId),
                        CLAIMED_USERS_KEY_TEMPLATE.formatted(couponId),
                        CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(couponId)
                )
        );
        if (rawValues == null || rawValues.size() != 3) {
            throw new IllegalStateException("秒杀券Redis结算证据读取结果不完整，couponId=" + couponId);
        }

        return new SettlementEvidenceSnapshot(
                parseNullableLong(rawValues.get(0)),
                parseRequiredLong(rawValues.get(1), "users"),
                parseRequiredLong(rawValues.get(2), "claims")
        );
    }

    /**
     * 活动结算一致后统一缩短三类证据Key的剩余生存时间，避免立即删除影响故障追查。
     */
    public void scheduleEvidenceCleanup(Long couponId, Duration retention) {
        if (couponId == null) {
            throw new IllegalArgumentException("优惠券ID不能为空");
        }
        Duration safeRetention = GovernanceTiming.positiveOrDefault(retention, Duration.ofDays(1));
        Instant cleanupInstant = Instant.now().plus(safeRetention);
        stringRedisTemplate.expireAt(ACTIVITY_KEY_TEMPLATE.formatted(couponId), cleanupInstant);
        stringRedisTemplate.expireAt(CLAIMED_USERS_KEY_TEMPLATE.formatted(couponId), cleanupInstant);
        stringRedisTemplate.expireAt(CLAIM_TRANSACTIONS_KEY_TEMPLATE.formatted(couponId), cleanupInstant);
    }

    /**
     * 创建原子读取结算证据的只读Lua脚本。
     */
    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> buildSettlementEvidenceScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setResultType(List.class);
        script.setScriptText(
                "local stock = redis.call('HGET', KEYS[1], 'stock'); "
                        + "if not stock then stock = '" + MISSING_VALUE + "'; end; "
                        + "return {stock, tostring(redis.call('SCARD', KEYS[2])), "
                        + "tostring(redis.call('HLEN', KEYS[3]))};"
        );
        return script;
    }

    /**
     * 解析允许缺失的Redis库存字段，活动Hash不存在时返回null。
     */
    private Long parseNullableLong(Object rawValue) {
        String value = decodeRedisValue(rawValue);
        if (value == null || MISSING_VALUE.equals(value)) {
            return null;
        }
        return parseLongValue(value, "stock");
    }

    /**
     * 解析必须存在的Redis计数结果。
     */
    private Long parseRequiredLong(Object rawValue, String fieldName) {
        String value = decodeRedisValue(rawValue);
        if (value == null) {
            throw new IllegalStateException("秒杀券Redis结算计数字段缺失：" + fieldName);
        }
        return parseLongValue(value, fieldName);
    }

    /**
     * 兼容脚本返回String或byte数组的序列化形式。
     */
    private String decodeRedisValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof byte[]) {
            return new String((byte[]) rawValue, StandardCharsets.UTF_8);
        }
        return String.valueOf(rawValue);
    }

    /**
     * 把Redis数字文本转换为Long，非法证据会阻止自动结算。
     */
    private Long parseLongValue(String value, String fieldName) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("秒杀券Redis结算字段不是合法数字：" + fieldName, exception);
        }
    }

    /**
     * 活动结束时一次原子读取到的Redis总账证据。
     */
    public static final class SettlementEvidenceSnapshot {

        // Redis活动Hash中的剩余库存，活动Hash缺失时为null。
        private final Long remainingStock;
        // Redis已领取用户集合中的成员数。
        private final Long userCount;
        // Redis领取流水Hash中的记录数。
        private final Long claimCount;

        /**
         * 创建不可变的Redis结算证据快照。
         */
        public SettlementEvidenceSnapshot(Long remainingStock, Long userCount, Long claimCount) {
            this.remainingStock = remainingStock;
            this.userCount = userCount;
            this.claimCount = claimCount;
        }

        /**
         * 返回Redis活动剩余库存，活动Hash缺失时为null。
         */
        public Long getRemainingStock() {
            return remainingStock;
        }

        /**
         * 返回Redis已领取用户集合数量。
         */
        public Long getUserCount() {
            return userCount;
        }

        /**
         * 返回Redis领取流水Hash数量。
         */
        public Long getClaimCount() {
            return claimCount;
        }
    }
}
