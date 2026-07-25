package com.sky.service;

import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;

import java.util.List;

/**
 * 秒杀券业务接口。
 */
public interface SeckillCouponService {

    /**
     * 新增秒杀券。
     *
     * @param seckillCouponDTO 秒杀券信息
     */
    void save(SeckillCouponDTO seckillCouponDTO);

    /**
     * 秒杀券分页查询。
     *
     * @param queryDTO 分页查询条件
     * @return 秒杀券分页数据
     */
    PageResult pageQuery(SeckillCouponPageQueryDTO queryDTO);

    /**
     * 修改秒杀券。
     *
     * @param seckillCouponDTO 秒杀券信息
     */
    void update(SeckillCouponDTO seckillCouponDTO);

    /**
     * 启用或停用秒杀券。
     *
     * @param status 秒杀券状态
     * @param id     秒杀券ID
     */
    void startOrStop(Integer status, Long id);

    /**
     * 用户端查询可领取的秒杀券。
     *
     * @return 可领取的秒杀券列表
     */
    List<SeckillCouponVO> listAvailable();

    /**
     * 当前登录用户领取秒杀券。
     *
     * 目前先完成 MySQL 事务版本；后续可以在实现层接入 Redis Lua
     * 进行库存预扣和一人一券校验。
     *
     * @param couponId 秒杀券ID
     * @return 用户领券记录ID
     */
    Long claim(Long couponId);

    /**
     * 用户端查询我的秒杀券。
     *
     * @return 当前用户的秒杀券列表
     */
    List<UserCouponVO> listMine();
}
