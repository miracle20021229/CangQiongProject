package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.SeckillCouponUserClaimService;
import com.sky.service.support.SeckillCouponFinder;
import com.sky.service.support.SeckillCouponValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户端秒杀券领取用例实现。
 */
@Service
public class SeckillCouponUserClaimServiceImpl
        implements SeckillCouponUserClaimService {

    private final SeckillCouponMapper seckillCouponMapper;
    private final UserCouponMapper userCouponMapper;
    private final SeckillCouponFinder seckillCouponFinder;
    private final SeckillCouponValidator seckillCouponValidator;

    public SeckillCouponUserClaimServiceImpl(
            SeckillCouponMapper seckillCouponMapper,
            UserCouponMapper userCouponMapper,
            SeckillCouponFinder seckillCouponFinder,
            SeckillCouponValidator seckillCouponValidator) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.userCouponMapper = userCouponMapper;
        this.seckillCouponFinder = seckillCouponFinder;
        this.seckillCouponValidator = seckillCouponValidator;
    }

    /**
     * 当前保留MySQL同步事务基线。
     * 流程3、4将在该独立用例内替换为Lua预扣、MQ投递和消费者独立落库事务。
     */
    @Override
    @Transactional
    public Long claim(Long couponId) {
        if (couponId == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }

        Long userId = BaseContext.getCurrentIdOrThrow();
        LocalDateTime now = LocalDateTime.now();
        SeckillCoupon coupon = seckillCouponFinder.getByIdOrThrow(couponId);

        // TODO 流程3：请求入口改为从Redis读取活动并执行Lua，避免秒杀请求先访问MySQL。
        seckillCouponValidator.validateClaimable(coupon, now);

        Integer claimedCount =
                userCouponMapper.countByCouponIdAndUserId(couponId, userId);
        if (claimedCount != null && claimedCount > 0) {
            throw new CouponBusinessException("每位用户限领一张，请勿重复领取");
        }

        // TODO 流程3：Lua成功后发送MQ并立即返回领取流水ID。
        // TODO 流程4：将条件扣库存和新增用户券移动到MQ消费者调用的独立事务Service。
        int affectedRows = seckillCouponMapper.decreaseStock(couponId, now);
        if (affectedRows != 1) {
            throw new CouponBusinessException("秒杀券已抢完或活动状态已变化，请刷新后重试");
        }

        UserCoupon userCoupon = UserCoupon.builder()
                .couponId(couponId)
                .userId(userId)
                .status(UserCoupon.UNUSED)
                .claimTime(now)
                .expireTime(coupon.getClaimEndTime())
                .build();

        try {
            userCouponMapper.insert(userCoupon);
        } catch (DuplicateKeyException exception) {
            // 唯一索引uk_coupon_user是并发场景下“一人一券”的最终兜底。
            throw new CouponBusinessException("每位用户限领一张，请勿重复领取");
        }

        return userCoupon.getId();
    }
}
