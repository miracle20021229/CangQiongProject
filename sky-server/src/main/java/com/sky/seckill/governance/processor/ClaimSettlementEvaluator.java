package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimSettlement;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import com.sky.seckill.redis.SeckillCouponRedisRepository.SettlementEvidenceSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 流程6C总账判定器，只读取多方事实并生成一致性结论。
 */
@Component
public class ClaimSettlementEvaluator {

    private final SeckillCouponClaimFailureMapper failureMapper;
    private final SeckillCouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final SeckillCouponRedisRepository redisRepository;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimSettlementEvaluator(
            SeckillCouponClaimFailureMapper failureMapper,
            SeckillCouponMapper couponMapper,
            UserCouponMapper userCouponMapper,
            SeckillCouponRedisRepository redisRepository,
            SeckillCouponClaimReconciliationProperties properties) {
        this.failureMapper = failureMapper;
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.redisRepository = redisRepository;
        this.properties = properties;
    }

    /**
     * 核对MySQL库存、领取数、Redis库存、users、claims和未解决治理数。
     */
    Decision evaluate(SeckillCouponClaimSettlement settlement, LocalDateTime now) {
        SeckillCoupon coupon = couponMapper.getById(settlement.getCouponId());
        if (coupon == null || coupon.getTotalStock() == null || coupon.getRemainingStock() == null
                || coupon.getClaimEndTime() == null) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_CONFLICT,
                    "活动不存在或库存、结束时间字段不完整",
                    null);
        }

        long mysqlClaimCount = userCouponMapper.countByCouponId(coupon.getId());
        long unresolvedCount = failureMapper.countUnresolvedByCouponId(coupon.getId());
        SettlementEvidenceSnapshot redisEvidence =
                redisRepository.readSettlementEvidence(coupon.getId());
        Snapshot snapshot = Snapshot.of(coupon, mysqlClaimCount, redisEvidence, unresolvedCount);

        if (redisEvidence.getRemainingStock() == null) {
            return hasExceededManualThreshold(coupon, now)
                    ? Decision.manual(
                            SeckillCouponClaimResolutionCode.ACTIVITY_REDIS_EVIDENCE_MISSING,
                            "活动Hash已经缺失，无法证明Redis最终库存",
                            snapshot)
                    : Decision.recheck(
                            SeckillCouponClaimResolutionCode.ACTIVITY_REDIS_EVIDENCE_MISSING,
                            "活动Hash暂时缺失，等待退避后复查",
                            snapshot);
        }
        if (snapshot.isConsistent()) {
            return Decision.consistent(
                    SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_CONSISTENT,
                    snapshot.summary(),
                    snapshot);
        }
        return hasExceededManualThreshold(coupon, now)
                ? Decision.manual(
                        SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_CONFLICT,
                        snapshot.summary(),
                        snapshot)
                : Decision.recheck(
                        SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_PENDING,
                        snapshot.summary(),
                        snapshot);
    }

    private boolean hasExceededManualThreshold(SeckillCoupon coupon, LocalDateTime now) {
        Duration manualAfter = GovernanceTiming.positiveOrDefault(
                properties.getSettlementManualAfter(), Duration.ofHours(2));
        return !coupon.getClaimEndTime().plus(manualAfter).isAfter(now);
    }

    /**
     * 一次活动总账核对读取到的不可变事实快照。
     */
    record Snapshot(
            Integer totalStock,
            Integer mysqlRemainingStock,
            Long mysqlClaimCount,
            Long redisRemainingStock,
            Long redisUserCount,
            Long redisClaimCount,
            Long unresolvedFailureCount) {

        static Snapshot of(
                SeckillCoupon coupon,
                long mysqlClaimCount,
                SettlementEvidenceSnapshot redisEvidence,
                long unresolvedFailureCount) {
            return new Snapshot(
                    coupon.getTotalStock(),
                    coupon.getRemainingStock(),
                    mysqlClaimCount,
                    redisEvidence.getRemainingStock(),
                    redisEvidence.getUserCount(),
                    redisEvidence.getClaimCount(),
                    unresolvedFailureCount);
        }

        boolean isConsistent() {
            if (totalStock < 0
                    || mysqlRemainingStock < 0
                    || mysqlRemainingStock > totalStock
                    || redisRemainingStock == null
                    || redisRemainingStock < 0
                    || redisRemainingStock > totalStock) {
                return false;
            }

            long mysqlAcceptedCount = (long) totalStock - mysqlRemainingStock;
            long redisAcceptedCount = (long) totalStock - redisRemainingStock;
            return mysqlAcceptedCount == mysqlClaimCount
                    && redisAcceptedCount == redisUserCount
                    && redisUserCount.equals(redisClaimCount)
                    && redisUserCount.equals(mysqlClaimCount)
                    && unresolvedFailureCount == 0L;
        }

        void applyTo(SeckillCouponClaimSettlement settlement) {
            settlement.setTotalStock(totalStock);
            settlement.setMysqlRemainingStock(mysqlRemainingStock);
            settlement.setMysqlClaimCount(mysqlClaimCount);
            settlement.setRedisRemainingStock(redisRemainingStock);
            settlement.setRedisUserCount(redisUserCount);
            settlement.setRedisClaimCount(redisClaimCount);
            settlement.setUnresolvedFailureCount(unresolvedFailureCount);
        }

        String summary() {
            return "totalStock=" + totalStock
                    + ", mysqlRemaining=" + mysqlRemainingStock
                    + ", mysqlClaims=" + mysqlClaimCount
                    + ", redisRemaining=" + redisRemainingStock
                    + ", redisUsers=" + redisUserCount
                    + ", redisClaims=" + redisClaimCount
                    + ", unresolvedFailures=" + unresolvedFailureCount;
        }
    }

    /**
     * 流程6C单个活动的结构化结算结果。
     */
    record Decision(
            String status,
            SeckillCouponClaimResolutionCode code,
            String message,
            Snapshot snapshot) {

        static Decision consistent(
                SeckillCouponClaimResolutionCode code,
                String message,
                Snapshot snapshot) {
            return new Decision(
                    SeckillCouponClaimSettlement.STATUS_CONSISTENT, code, message, snapshot);
        }

        static Decision recheck(
                SeckillCouponClaimResolutionCode code,
                String message,
                Snapshot snapshot) {
            return new Decision(
                    SeckillCouponClaimSettlement.STATUS_RECHECK_PENDING, code, message, snapshot);
        }

        static Decision manual(
                SeckillCouponClaimResolutionCode code,
                String message,
                Snapshot snapshot) {
            return new Decision(
                    SeckillCouponClaimSettlement.STATUS_MANUAL_REQUIRED, code, message, snapshot);
        }
    }
}
