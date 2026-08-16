package com.sky.seckill.governance.support;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.entity.UserCoupon;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import org.springframework.stereotype.Component;

/**
 * 流程6A/B共用的领取事实检查器。
 * 每次调用都重新读取MySQL或Redis，避免流程6B复用流程6A的过期判断结果。
 */
@Component
public class SeckillCouponClaimEvidenceInspector {

    // 查询MySQL用户券最终事实。
    private final UserCouponMapper userCouponMapper;
    // 查询秒杀券活动事实。
    private final SeckillCouponMapper seckillCouponMapper;
    // 查询Redis领取流水和已领取用户证据。
    private final SeckillCouponRedisRepository redisRepository;

    public SeckillCouponClaimEvidenceInspector(UserCouponMapper userCouponMapper,
                                               SeckillCouponMapper seckillCouponMapper,
                                               SeckillCouponRedisRepository redisRepository) {
        this.userCouponMapper = userCouponMapper;
        this.seckillCouponMapper = seckillCouponMapper;
        this.redisRepository = redisRepository;
    }

    /**
     * 按业务身份、claimId、一人一券和活动存在性顺序读取MySQL事实。
     */
    public MysqlEvidence inspectMysqlFacts(SeckillCouponClaimFailure failure) {
        if (!hasBusinessIdentity(failure)) {
            return new MysqlEvidence(
                    MysqlEvidenceState.BUSINESS_IDENTITY_MISSING,
                    null,
                    SeckillCouponClaimResolutionCode.BUSINESS_IDENTITY_MISSING,
                    "失败记录缺少claimId、couponId或userId"
            );
        }

        UserCoupon persistedByClaimId = userCouponMapper.getByClaimId(failure.getClaimId());
        if (persistedByClaimId != null) {
            return matches(persistedByClaimId, failure)
                    ? new MysqlEvidence(
                    MysqlEvidenceState.ALREADY_PERSISTED,
                    null,
                    SeckillCouponClaimResolutionCode.ALREADY_PERSISTED,
                    "MySQL已存在完全一致的领取记录")
                    : new MysqlEvidence(
                    MysqlEvidenceState.CLAIM_ID_CONFLICT,
                    null,
                    SeckillCouponClaimResolutionCode.CLAIM_ID_CONFLICT,
                    "claimId已对应其他券或其他用户");
        }

        UserCoupon persistedByUser = userCouponMapper.getByCouponIdAndUserId(
                failure.getCouponId(), failure.getUserId());
        if (persistedByUser != null) {
            return new MysqlEvidence(
                    MysqlEvidenceState.USER_COUPON_CONFLICT,
                    null,
                    SeckillCouponClaimResolutionCode.USER_COUPON_CONFLICT,
                    "用户已通过其他claimId领取该券"
            );
        }

        SeckillCoupon coupon = seckillCouponMapper.getById(failure.getCouponId());
        if (coupon == null) {
            return new MysqlEvidence(
                    MysqlEvidenceState.COUPON_NOT_FOUND,
                    null,
                    SeckillCouponClaimResolutionCode.COUPON_NOT_FOUND,
                    "秒杀券不存在，禁止自动处理领取记录"
            );
        }
        return new MysqlEvidence(MysqlEvidenceState.READY, coupon, null, null);
    }

    /**
     * 同时读取Redis领取流水和用户集合，并把两项证据归类为完整、冲突或缺失。
     */
    public RedisEvidenceState inspectRedisEvidence(SeckillCouponClaimFailure failure) {
        String redisClaimOwner = redisRepository.findClaimOwner(
                failure.getCouponId(), failure.getClaimId());
        boolean redisUserClaimed = redisRepository.isUserClaimed(
                failure.getCouponId(), failure.getUserId());
        if (String.valueOf(failure.getUserId()).equals(redisClaimOwner) && redisUserClaimed) {
            return RedisEvidenceState.CONFIRMED;
        }
        return redisClaimOwner != null || redisUserClaimed
                ? RedisEvidenceState.CONFLICT
                : RedisEvidenceState.MISSING;
    }

    /**
     * 判断MySQL用户券是否与失败治理记录指向同一笔领取。
     */
    public boolean matches(UserCoupon userCoupon, SeckillCouponClaimFailure failure) {
        return userCoupon != null
                && hasBusinessIdentity(failure)
                && failure.getClaimId().equals(userCoupon.getClaimId())
                && failure.getCouponId().equals(userCoupon.getCouponId())
                && failure.getUserId().equals(userCoupon.getUserId());
    }

    private boolean hasBusinessIdentity(SeckillCouponClaimFailure failure) {
        return failure != null
                && failure.getClaimId() != null
                && !failure.getClaimId().isBlank()
                && failure.getCouponId() != null
                && failure.getUserId() != null;
    }

    /**
     * MySQL领取事实检查结果。
     */
    public record MysqlEvidence(MysqlEvidenceState state,
                                SeckillCoupon coupon,
                                SeckillCouponClaimResolutionCode code,
                                String message) {
    }

    public enum MysqlEvidenceState {
        BUSINESS_IDENTITY_MISSING,
        ALREADY_PERSISTED,
        CLAIM_ID_CONFLICT,
        USER_COUPON_CONFLICT,
        COUPON_NOT_FOUND,
        READY
    }

    public enum RedisEvidenceState {
        CONFIRMED,
        CONFLICT,
        MISSING
    }
}
