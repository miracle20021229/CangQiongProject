package com.sky.service;

/**
 * 用户端秒杀券领取用例。
 *
 * 当前保留MySQL同步事务基线；流程3、4将在该边界内接入Lua、MQ和独立落库事务。
 */
public interface SeckillCouponUserClaimService {

    /**
     * 当前用户领取指定秒杀券。
     *
     * @param couponId 秒杀券ID
     * @return 用户领券记录ID
     */
    Long claim(Long couponId);
}
