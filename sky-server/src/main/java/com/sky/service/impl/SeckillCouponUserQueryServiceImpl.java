package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.SeckillCouponUserQueryService;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_KEY;
import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TTL_SECONDS;
import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TYPE;

/**
 * 秒杀券用户查询用例实现。
 */
@Service
public class SeckillCouponUserQueryServiceImpl implements SeckillCouponUserQueryService {

    private final SeckillCouponMapper seckillCouponMapper;
    private final UserCouponMapper userCouponMapper;
    private final CacheClient cacheClient;

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
