package com.sky.seckill.exception;

import com.sky.seckill.enums.SeckillCouponClaimFailureCode;

/**
 * 领取落库时已经可确定分类的业务失败。
 */
public class SeckillCouponClaimPersistenceException extends RuntimeException {

    // 已经完成稳定分类的领取落库失败码。
    private final SeckillCouponClaimFailureCode failureCode;

    /**
     * 创建携带稳定失败码的领取落库异常。
     *
     * @param failureCode 决定重试或治理路由的稳定失败码
     * @param message     供日志和治理记录使用的异常摘要
     */
    public SeckillCouponClaimPersistenceException(SeckillCouponClaimFailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    /**
     * 返回领取落库阶段已经确定的稳定失败码。
     *
     * @return 稳定失败码
     */
    public SeckillCouponClaimFailureCode getFailureCode() {
        return failureCode;
    }
}
