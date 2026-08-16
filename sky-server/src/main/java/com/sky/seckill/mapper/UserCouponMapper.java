package com.sky.seckill.mapper;

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
     * 根据领取流水ID查询已落库记录，用于MQ重复消费幂等判断。
     */
    UserCoupon getByClaimId(@Param("claimId") String claimId);

    /**
     * 根据券和用户查询已领取记录，用于识别非同一 claimId 的一人一券冲突。
     */
    UserCoupon getByCouponIdAndUserId(@Param("couponId") Long couponId, @Param("userId") Long userId);

    /**
     * 按领取流水和当前用户查询记录，供用户侧状态接口校验数据归属。
     */
    UserCoupon getByClaimIdAndUserId(@Param("claimId") String claimId, @Param("userId") Long userId);

    /**
     * 统计指定活动已经最终落库的用户券数量，供流程6C核对总账。
     */
    long countByCouponId(@Param("couponId") Long couponId);

    /**
     * 查询指定用户领取过的全部秒杀券。
     */
    List<UserCouponVO> listByUserId(@Param("userId") Long userId);
}
