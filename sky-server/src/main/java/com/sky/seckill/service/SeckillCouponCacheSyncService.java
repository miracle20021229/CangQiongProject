package com.sky.seckill.service;

/**
 * 秒杀券缓存投影同步用例。
 *
 * 列表缓存以MySQL为准重建；活动快照区分状态同步和缺失修复，避免覆盖Lua预扣现场。
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
     * 状态发生变化时同步Redis活动：启用时完整初始化，停用时只更新状态。
     */
    void synchronizeCouponActivity(Long couponId);

    /**
     * Redis活动快照缺失或字段不完整时按MySQL修复。
     *
     * @return true表示执行了修复，false表示原快照完整
     */
    boolean repairCouponActivity(Long couponId);
}
