package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户秒杀券展示数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCouponVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户领券记录ID。 */
    private Long id;

    /** 对应的秒杀券活动ID。 */
    private Long couponId;

    /** 秒杀券名称。 */
    private String couponName;

    /** 使用门槛金额，0表示无门槛。 */
    private BigDecimal thresholdAmount;

    /** 优惠金额。 */
    private BigDecimal discountAmount;

    /** 使用状态：0未使用，1已使用，2已过期。 */
    private Integer status;

    /** 领取时间。 */
    private LocalDateTime claimTime;

    /** 使用截止时间。 */
    private LocalDateTime expireTime;

    /** 实际使用时间，未使用时为空。 */
    private LocalDateTime useTime;

    /** 使用该券的订单ID，未使用时为空。 */
    private Long orderId;
}
