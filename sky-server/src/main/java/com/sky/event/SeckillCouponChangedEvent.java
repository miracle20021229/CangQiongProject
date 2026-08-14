package com.sky.event;

/**
 * 秒杀券数据已变更事件。
 *
 * @param couponId  秒杀券ID
 * @param changeType 变更类型
 */
public record SeckillCouponChangedEvent(
        Long couponId,
        ChangeType changeType) {

    /**
     * 秒杀券数据变更类型。
     */
    public enum ChangeType {
        CREATED,
        UPDATED,
        STATUS_CHANGED,
        ACTIVITY_REPAIR_REQUESTED
    }
}
