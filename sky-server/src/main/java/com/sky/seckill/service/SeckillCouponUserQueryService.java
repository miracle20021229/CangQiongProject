package com.sky.seckill.service;

import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;

import java.util.List;

/**
 * 秒杀券用户查询用例。
 */
public interface SeckillCouponUserQueryService {

    /**
     * 查询当前可领取的秒杀券。
     *
     * @return 可领取秒杀券列表
     */
    List<SeckillCouponVO> listAvailable();

    /**
     * 查询当前用户的秒杀券。
     *
     * @return 用户秒杀券列表
     */
    List<UserCouponVO> listMine();
}
