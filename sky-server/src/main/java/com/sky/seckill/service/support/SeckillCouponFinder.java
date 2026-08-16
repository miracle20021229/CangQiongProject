package com.sky.seckill.service.support;

import com.sky.entity.SeckillCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.seckill.mapper.SeckillCouponMapper;
import org.springframework.stereotype.Component;

/**
 * 秒杀券必存查询组件。
 */
@Component
public class SeckillCouponFinder {

    // 查询秒杀券活动的数据访问接口。
    private final SeckillCouponMapper seckillCouponMapper;

    /**
     * 创建秒杀券必存查询组件。
     *
     * @param seckillCouponMapper 秒杀券数据访问接口
     */
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
