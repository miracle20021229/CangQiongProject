package com.sky.service.support;

import com.sky.entity.SeckillCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.SeckillCouponMapper;
import org.springframework.stereotype.Component;

/**
 * 秒杀券必存查询组件。
 */
@Component
public class SeckillCouponFinder {

    private final SeckillCouponMapper seckillCouponMapper;

    public SeckillCouponFinder(SeckillCouponMapper seckillCouponMapper) {
        this.seckillCouponMapper = seckillCouponMapper;
    }

    /**
     * 查询必须存在的秒杀券。
     */
    public SeckillCoupon getByIdOrThrow(Long couponId) {
        SeckillCoupon coupon = seckillCouponMapper.getById(couponId);
        if (coupon == null) {
            throw new CouponBusinessException("秒杀券不存在");
        }
        return coupon;
    }
}
