package com.sky.service.support;

import com.sky.constant.StatusConstant;
import com.sky.dto.SeckillCouponDTO;
import com.sky.entity.SeckillCoupon;
import com.sky.exception.CouponBusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀券业务规则校验器。
 * 只负责纯业务规则，不访问数据库、Redis或MQ。
 */
@Component
public class SeckillCouponValidator {

    /**
     * 校验新增秒杀券参数。
     */
    public void validateForSave(SeckillCouponDTO seckillCouponDTO) {
        validateCoupon(seckillCouponDTO, false);
    }

    /**
     * 校验修改秒杀券参数。
     */
    public void validateForUpdate(SeckillCouponDTO seckillCouponDTO) {
        validateCoupon(seckillCouponDTO, true);
    }

    /**
     * 校验秒杀券是否允许领取。
     */
    public void validateClaimable(SeckillCoupon coupon, LocalDateTime now) {
        if (!StatusConstant.ENABLE.equals(coupon.getStatus())) {
            throw new CouponBusinessException("秒杀券未启用");
        }
        if (coupon.getClaimStartTime() == null || now.isBefore(coupon.getClaimStartTime())) {
            throw new CouponBusinessException("秒杀券领取活动尚未开始");
        }
        if (coupon.getClaimEndTime() == null || !now.isBefore(coupon.getClaimEndTime())) {
            throw new CouponBusinessException("秒杀券领取活动已结束");
        }
        if (coupon.getRemainingStock() == null || coupon.getRemainingStock() <= 0) {
            throw new CouponBusinessException("秒杀券已抢完");
        }
    }

    /**
     * 校验新增和修改共用的业务字段。
     */
    private void validateCoupon(SeckillCouponDTO seckillCouponDTO, boolean requireId) {
        if (seckillCouponDTO == null) {
            throw new CouponBusinessException("秒杀券信息不能为空");
        }
        if (requireId && seckillCouponDTO.getId() == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }
        if (!StringUtils.hasText(seckillCouponDTO.getName())) {
            throw new CouponBusinessException("秒杀券名称不能为空");
        }
        if (seckillCouponDTO.getName().trim().length() > 64) {
            throw new CouponBusinessException("秒杀券名称长度不能超过64个字符");
        }
        if (seckillCouponDTO.getThresholdAmount() == null
                || seckillCouponDTO.getThresholdAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new CouponBusinessException("使用门槛金额不能小于0");
        }
        if (seckillCouponDTO.getDiscountAmount() == null
                || seckillCouponDTO.getDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CouponBusinessException("优惠金额必须大于0");
        }
        if (seckillCouponDTO.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0
                && seckillCouponDTO.getDiscountAmount()
                .compareTo(seckillCouponDTO.getThresholdAmount()) > 0) {
            throw new CouponBusinessException("优惠金额不能大于使用门槛金额");
        }
        if (seckillCouponDTO.getTotalStock() == null || seckillCouponDTO.getTotalStock() <= 0) {
            throw new CouponBusinessException("总库存必须大于0");
        }
        if (seckillCouponDTO.getClaimStartTime() == null
                || seckillCouponDTO.getClaimEndTime() == null) {
            throw new CouponBusinessException("领取开始时间和结束时间不能为空");
        }
        if (!seckillCouponDTO.getClaimStartTime().isBefore(seckillCouponDTO.getClaimEndTime())) {
            throw new CouponBusinessException("领取开始时间必须早于结束时间");
        }
    }
}
