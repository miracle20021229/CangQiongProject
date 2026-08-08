package com.sky.constant;

import com.alibaba.fastjson2.TypeReference;
import com.sky.vo.SeckillCouponVO;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 秒杀券缓存常量。
 * 统一可领取列表查询、预热和重建使用的缓存配置，避免读写策略不一致。
 */
public final class SeckillCouponCacheConstant {

    public static final String AVAILABLE_LIST_KEY = "cache:seckill:coupon:available";
    public static final Type AVAILABLE_LIST_TYPE = new TypeReference<List<SeckillCouponVO>>() {}.getType();
    public static final long AVAILABLE_LIST_TTL_SECONDS = 30L;

    private SeckillCouponCacheConstant() {
    }
}
