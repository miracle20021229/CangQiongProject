package com.sky.seckill.service;

import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.result.PageResult;

/**
 * 管理端秒杀券用例。
 *
 * 负责管理端的新增、查询、修改和启停，不包含Redis与RocketMQ技术细节。
 */
public interface SeckillCouponAdminService {

    /**
     * 新增秒杀券。
     *
     * @param seckillCouponDTO 秒杀券信息
     */
    void save(SeckillCouponDTO seckillCouponDTO);

    /**
     * 分页查询秒杀券。
     *
     * @param queryDTO 分页查询条件
     * @return 分页结果
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
     * @param status 目标状态
     * @param id     秒杀券ID
     */
    void startOrStop(Integer status, Long id);
}
