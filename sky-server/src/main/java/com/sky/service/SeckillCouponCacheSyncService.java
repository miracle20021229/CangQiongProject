package com.sky.service;

/**
 * 秒杀券缓存投影同步用例。
 *
 * 所有写操作都以MySQL最新状态为准幂等覆盖Redis。
 * 该服务不吞掉异常：普通入口由监听器发送补偿消息，MQ入口由Broker负责重试。
 */
public interface SeckillCouponCacheSyncService {

    /**
     * 启动时预热可领取秒杀券缓存。
     */
    void warmUpAvailableCouponCache();

    /**
     * 以MySQL最新数据重建可领取列表缓存。
     */
    int rebuildAvailableCouponCache();

    /**
     * 以MySQL最新数据同步Redis活动快照。
     */
    void synchronizeCouponActivity(Long couponId);
}
