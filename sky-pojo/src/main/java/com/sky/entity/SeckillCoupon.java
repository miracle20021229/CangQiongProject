package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀券对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCoupon implements Serializable {

    public static final Integer DISABLED = 0;
    public static final Integer ENABLED = 1;

    private static final long serialVersionUID = 1L;

    private Long id;

    // 券名称
    private String name;

    // 使用门槛金额
    private BigDecimal thresholdAmount;

    // 优惠金额
    private BigDecimal discountAmount;

    // 总库存
    private Integer totalStock;

    // 剩余库存
    private Integer remainingStock;

    // 开始领取时间
    private LocalDateTime claimStartTime;

    // 结束领取时间
    private LocalDateTime claimEndTime;

    // 状态：0停用，1启用
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
