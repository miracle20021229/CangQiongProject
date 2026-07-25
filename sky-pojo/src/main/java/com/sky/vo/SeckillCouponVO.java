package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀券展示数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀券ID。 */
    private Long id;

    /** 秒杀券名称。 */
    private String name;

    /** 使用门槛金额，0表示无门槛。 */
    private BigDecimal thresholdAmount;

    /** 优惠金额。 */
    private BigDecimal discountAmount;

    /** 活动总库存。 */
    private Integer totalStock;

    /** 当前剩余库存。 */
    private Integer remainingStock;

    /** 开始领取时间。 */
    private LocalDateTime claimStartTime;

    /** 结束领取时间。 */
    private LocalDateTime claimEndTime;

    /** 活动状态：0停用，1启用。 */
    private Integer status;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 最后修改时间。 */
    private LocalDateTime updateTime;
}
