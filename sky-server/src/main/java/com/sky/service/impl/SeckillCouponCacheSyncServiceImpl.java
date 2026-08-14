package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.entity.SeckillCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.redis.SeckillCouponRedisRepository;
import com.sky.service.SeckillCouponCacheSyncService;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_KEY;
import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TTL_SECONDS;
import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TYPE;

/**
 * 秒杀券缓存投影同步实现。
 */
@Service
@Slf4j
public class SeckillCouponCacheSyncServiceImpl implements SeckillCouponCacheSyncService {

    private final SeckillCouponMapper seckillCouponMapper;
    private final CacheClient cacheClient;
    private final SeckillCouponRedisRepository seckillCouponRedisRepository;

    public SeckillCouponCacheSyncServiceImpl(SeckillCouponMapper seckillCouponMapper, CacheClient cacheClient, SeckillCouponRedisRepository seckillCouponRedisRepository) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.cacheClient = cacheClient;
        this.seckillCouponRedisRepository = seckillCouponRedisRepository;
    }

    /**
     * 应用启动时预热可领取列表缓存。
     */
    @Override
    public void warmUpAvailableCouponCache() {
        cacheClient.warmUpWithLogicalExpire(
                AVAILABLE_LIST_KEY,
                AVAILABLE_LIST_TYPE,
                () -> seckillCouponMapper.listAvailable(LocalDateTime.now()),
                AVAILABLE_LIST_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 查询MySQL并覆盖可领取列表缓存。
     */
    @Override
    public int rebuildAvailableCouponCache() {
        List<SeckillCouponVO> coupons = seckillCouponMapper.listAvailable(LocalDateTime.now());
        cacheClient.setWithLogicalExpire(AVAILABLE_LIST_KEY, coupons, AVAILABLE_LIST_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("可领取秒杀券缓存重建完成，数量={}", coupons.size());
        return coupons.size();
    }

    /**
     * 状态发生变化时重新查询MySQL：启用则完整初始化，停用则只更新状态。
     */
    @Override
    public void synchronizeCouponActivity(Long couponId) {
        if (couponId == null) {
            throw new IllegalArgumentException("同步Redis活动快照时couponId不能为空");
        }

        SeckillCoupon coupon = seckillCouponMapper.getById(couponId);
        if (coupon == null) {
            throw new IllegalStateException("同步Redis活动失败，秒杀券不存在，couponId=" + couponId);
        }

        if (StatusConstant.ENABLE.equals(coupon.getStatus())) {
            seckillCouponRedisRepository.initializeActivity(coupon);
        } else {
            seckillCouponRedisRepository.updateActivityStatus(coupon.getId(), coupon.getStatus());
        }
        log.info("Redis秒杀活动同步完成，couponId={}，status={}",
                coupon.getId(), coupon.getStatus());
    }

    /**
     * 快照完整时保留Redis现场，缺失或损坏时才按MySQL重建。
     */
    @Override
    public boolean repairCouponActivity(Long couponId) {
        if (couponId == null) {
            throw new IllegalArgumentException("修复Redis活动快照时couponId不能为空");
        }

        SeckillCoupon coupon = seckillCouponMapper.getById(couponId);
        if (coupon == null) {
            throw new IllegalStateException("修复Redis活动失败，秒杀券不存在，couponId=" + couponId);
        }
        if (seckillCouponRedisRepository.isActivityComplete(couponId)) {
            return false;
        }

        seckillCouponRedisRepository.initializeActivity(coupon);
        log.info("Redis秒杀活动快照不完整，已按MySQL修复，couponId={}", couponId);
        return true;
    }
}
