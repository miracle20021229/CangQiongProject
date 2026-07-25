package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户领取到的秒杀券
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCoupon implements Serializable {

    public static final Integer UNUSED = 0;
    public static final Integer USED = 1;
    public static final Integer EXPIRED = 2;

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long couponId;

    private Long userId;

    // 状态：0未使用，1已使用，2已过期
    private Integer status;

    private LocalDateTime claimTime;

    private LocalDateTime expireTime;

    private LocalDateTime useTime;

    // 后续接入外卖订单时使用，当前允许为空
    private Long orderId;
}
