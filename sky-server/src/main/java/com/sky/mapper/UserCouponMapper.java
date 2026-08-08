package com.sky.mapper;

import com.sky.entity.UserCoupon;
import com.sky.vo.UserCouponVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户领券记录数据访问接口。
 */
@Mapper
public interface UserCouponMapper {

    /**
     * 新增用户领券记录，并回填主键ID。
     */
    void insert(UserCoupon userCoupon);

    /**
     * 查询用户是否已经领取过指定秒杀券。
     */
    Integer countByCouponIdAndUserId(@Param("couponId") Long couponId, @Param("userId") Long userId);

    /**
     * 查询指定用户领取过的全部秒杀券。
     */
    List<UserCouponVO> listByUserId(@Param("userId") Long userId);
}
