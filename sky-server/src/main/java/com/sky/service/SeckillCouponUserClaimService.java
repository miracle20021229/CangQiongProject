package com.sky.service;

/**
 * 用户端秒杀券领取用例。
 *
 * 流程3负责Lua原子预扣和事务消息提交，流程4由MQ消费者异步落库。
 */
public interface SeckillCouponUserClaimService {

    /**
     * 当前用户领取指定秒杀券。
     */
    String claim(Long couponId);
}
