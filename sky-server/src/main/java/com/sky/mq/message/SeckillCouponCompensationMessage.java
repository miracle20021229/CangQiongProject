package com.sky.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流程2秒杀券Redis补偿消息。
 *
 * 消息只保存定位补偿任务所需的信息，消费者始终重新查询MySQL，
 * 避免使用发送时已经过期的优惠券状态覆盖Redis。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponCompensationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一标识，同时作为RocketMQ消息Key。
     */
    private String eventId;

    /**
     * 消息结构版本，便于后续兼容升级。
     */
    private Integer schemaVersion;

    /**
     * 补偿类型。
     */
    private CompensationType type;

    /**
     * 秒杀券ID；列表缓存重建消息不需要该字段。
     */
    private Long couponId;

    /**
     * 触发补偿的业务场景，仅用于日志与排查。
     */
    private String reason;

    /**
     * 消息创建时间，Unix毫秒时间戳。
     */
    private Long occurredAt;

    /**
     * 流程2秒杀券Redis补偿类型。
     */
    public enum CompensationType {

        /**
         * 重建用户端可领取秒杀券列表缓存。
         */
        AVAILABLE_CACHE_REBUILD,

        /**
         * 按数据库最新状态重新同步秒杀活动快照。
         */
        ACTIVITY_SNAPSHOT_SYNC,

        /**
         * 仅在秒杀活动快照不完整时按数据库修复。
         */
        ACTIVITY_SNAPSHOT_REPAIR
    }
}
