package com.sky.seckill.service;

import com.sky.vo.SeckillCouponClaimStatusVO;

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

    /**
     * 查询当前用户某个异步领取流水的最终业务状态。
     */
    SeckillCouponClaimStatusVO getClaimStatus(String claimId);
}
