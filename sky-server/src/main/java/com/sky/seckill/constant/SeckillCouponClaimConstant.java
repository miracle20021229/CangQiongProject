package com.sky.seckill.constant;

/**
 * 秒杀券领取流程常量。
 */
public final class SeckillCouponClaimConstant {

    // Lua原子预扣成功返回码。
    public static final Long SUCCESS = 0L;
    // Redis剩余库存不足返回码。
    public static final Long OUT_OF_STOCK = 1L;
    // 当前用户已经领取返回码。
    public static final Long DUPLICATE_CLAIM = 2L;
    // Redis活动快照尚未初始化返回码。
    public static final Long ACTIVITY_NOT_INITIALIZED = 3L;
    // 秒杀券活动处于停用状态返回码。
    public static final Long ACTIVITY_DISABLED = 4L;
    // 当前时间早于活动开始时间返回码。
    public static final Long ACTIVITY_NOT_STARTED = 5L;
    // 当前时间晚于活动结束时间返回码。
    public static final Long ACTIVITY_ENDED = 6L;
    // 主领取消息使用的RocketMQ Tag。
    public static final String CLAIM_TAG = "CLAIM";
    // 失败治理消息使用的RocketMQ Tag。
    public static final String FAILURE_TAG = "FAILURE";
    // 主领取消费者支持的消息结构版本。
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    // 失败治理消息当前结构版本。
    public static final int FAILURE_MESSAGE_SCHEMA_VERSION = 1;
    // 主消费和治理消费允许的最大重复消费次数。
    public static final int MAX_RECONSUME_TIMES = 5;

    /**
     * 常量类不允许创建实例。
     */
    private SeckillCouponClaimConstant() {
    }
}
