package com.sky.seckill.service.impl;

import com.sky.context.BaseContext;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.service.SeckillCouponUserQueryService;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.sky.seckill.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_KEY;
import static com.sky.seckill.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TTL_SECONDS;
import static com.sky.seckill.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TYPE;

/**
 * 秒杀券用户查询用例实现。
 */
@Service
public class SeckillCouponUserQueryServiceImpl implements SeckillCouponUserQueryService {

    // 查询可领取秒杀券活动的数据访问接口。
    private final SeckillCouponMapper seckillCouponMapper;
    // 查询当前用户已领取券的数据访问接口。
    private final UserCouponMapper userCouponMapper;
    // 读取和重建普通展示缓存的通用组件。
    private final CacheClient cacheClient;

    /**
     * 创建用户端秒杀券查询服务。
     *
     * @param seckillCouponMapper 秒杀券数据访问接口
     * @param userCouponMapper 用户券数据访问接口
     * @param cacheClient 普通展示缓存组件
     */
    public SeckillCouponUserQueryServiceImpl(SeckillCouponMapper seckillCouponMapper, UserCouponMapper userCouponMapper, CacheClient cacheClient) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.userCouponMapper = userCouponMapper;
        this.cacheClient = cacheClient;
    }

    /**
     * 查询逻辑过期缓存中的可领取秒杀券。
     */
    @Override
    public List<SeckillCouponVO> listAvailable() {
        return cacheClient.queryWithLogicalExpire(
                AVAILABLE_LIST_KEY,
                AVAILABLE_LIST_TYPE,
                () -> seckillCouponMapper.listAvailable(LocalDateTime.now()),
                AVAILABLE_LIST_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 查询当前登录用户的秒杀券。
     */
    @Override
    public List<UserCouponVO> listMine() {
        Long userId = BaseContext.getCurrentIdOrThrow();
        return userCouponMapper.listByUserId(userId);
    }
}
