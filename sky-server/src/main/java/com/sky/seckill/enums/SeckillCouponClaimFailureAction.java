package com.sky.seckill.enums;

/**
 * 流程5领取失败处置动作。
 */
public enum SeckillCouponClaimFailureAction {

    // 仅适用于小概率、可恢复的临时故障。
    RETRY(null),

    // 非法消息或非瞬时系统错误，等待人工检查。
    QUARANTINE("QUARANTINED"),

    // Redis、MySQL或领取流水存在一致性差异，等待流程6对账。
    RECONCILE("RECONCILE_PENDING"),

    // 明确可重试故障耗尽Broker重试预算。
    DEAD_LETTER("DEAD_LETTERED");

    // 该处置动作落库后对应的初始治理状态；仅重试动作不落治理表。
    private final String recordStatus;

    /**
     * 创建失败处置动作并绑定其初始治理状态。
     *
     * @param recordStatus 失败事实落库后的状态，纯重试动作为null
     */
    SeckillCouponClaimFailureAction(String recordStatus) {
        this.recordStatus = recordStatus;
    }

    /**
     * 返回该处置动作写入失败治理表时使用的初始状态。
     *
     * @return 初始治理状态，纯重试动作为null
     */
    public String getRecordStatus() {
        return recordStatus;
    }

    /**
     * 判断当前动作是否必须把失败事实转发到独立治理Topic。
     *
     * @return 非RETRY动作返回true
     */
    public boolean requiresFailureRouting() {
        return this != RETRY;
    }
}
