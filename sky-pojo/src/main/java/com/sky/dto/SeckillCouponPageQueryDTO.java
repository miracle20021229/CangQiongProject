package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀券分页查询参数。
 */
@Data
public class SeckillCouponPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private int page;

    private int pageSize;

    private String name;

    // 状态：0停用，1启用
    private Integer status;
}
