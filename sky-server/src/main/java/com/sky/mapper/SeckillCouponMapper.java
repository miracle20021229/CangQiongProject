package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.entity.SeckillCoupon;
import com.sky.enumeration.OperationType;
import com.sky.vo.SeckillCouponVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀券数据访问接口。
 */
@Mapper
public interface SeckillCouponMapper {

    /**
     * 新增秒杀券活动。
     *
     * @param seckillCoupon 秒杀券实体
     */
    @AutoFill(OperationType.INSERT)
    void insert(SeckillCoupon seckillCoupon);

    /**
     * 管理端分页查询秒杀券。
     *
     * @param queryDTO 分页查询条件
     * @return 分页数据
     */
    Page<SeckillCouponVO> pageQuery(SeckillCouponPageQueryDTO queryDTO);

    /**
     * 修改秒杀券活动。
     *
     * @param seckillCoupon 秒杀券实体
     */
    @AutoFill(OperationType.UPDATE)
    void update(SeckillCoupon seckillCoupon);

    /**
     * 根据ID查询秒杀券实体。
     *
     * @param id 秒杀券ID
     * @return 秒杀券实体，不存在时返回null
     */
    SeckillCoupon getById(Long id);

    /**
     * 查询当前可领取的秒杀券。
     *
     * @param now 当前时间
     * @return 可领取的秒杀券列表
     */
    List<SeckillCouponVO> listAvailable(@Param("now") LocalDateTime now);

    /**
     * 查询仍未结束的已启用活动，用于项目启动时恢复Redis秒杀活动快照。
     *
     * @param now 当前时间
     * @return 已启用且未结束的秒杀券
     */
    List<SeckillCoupon> listEnabledNotEnded(@Param("now") LocalDateTime now);

    /**
     * 在状态、时间和库存均符合条件时原子扣减一张数据库库存。
     *
     * @param id  秒杀券ID
     * @param now 当前时间
     * @return 受影响行数，1表示扣减成功，0表示活动不可领取或库存不足
     */
    int decreaseStock(@Param("id") Long id, @Param("now") LocalDateTime now);
}
