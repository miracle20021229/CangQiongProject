package com.sky.seckill.enums;

/**
 * 秒杀券领取失败码。
 * 只保留会影响重试、告警和后续对账路由的稳定类型。
 */
public enum SeckillCouponClaimFailureCode {

    // 消息正文无法解析或必填字段不完整。
    INVALID_MESSAGE(SeckillCouponClaimFailureAction.QUARANTINE),
    // 消息结构版本不在当前消费者支持范围内。
    UNSUPPORTED_SCHEMA_VERSION(SeckillCouponClaimFailureAction.QUARANTINE),
    // 同一claimId已经绑定其他券或用户。
    CLAIM_ID_CONFLICT(SeckillCouponClaimFailureAction.QUARANTINE),
    // Redis已产生领取证据但MySQL活动不存在。
    COUPON_NOT_FOUND(SeckillCouponClaimFailureAction.RECONCILE),
    // Redis已预扣但MySQL条件扣减库存失败。
    MYSQL_STOCK_CONFLICT(SeckillCouponClaimFailureAction.RECONCILE),
    // 用户已经通过另一条claimId领取该券。
    USER_ALREADY_CLAIMED(SeckillCouponClaimFailureAction.RECONCILE),
    // 可能由并发事务导致、允许一次重试的唯一键竞争。
    MYSQL_DUPLICATE_RACE(SeckillCouponClaimFailureAction.RETRY),
    // 重试后仍无法证明幂等成功的唯一键冲突。
    MYSQL_DUPLICATE_CONFLICT(SeckillCouponClaimFailureAction.RECONCILE),
    // 网络、连接池、死锁等可恢复数据库故障。
    MYSQL_TRANSIENT_FAILURE(SeckillCouponClaimFailureAction.RETRY),
    // SQL、表结构或数据约束等重试无效的数据库故障。
    MYSQL_NON_TRANSIENT_FAILURE(SeckillCouponClaimFailureAction.QUARANTINE),
    // 未被稳定规则识别的运行时异常。
    UNEXPECTED_FAILURE(SeckillCouponClaimFailureAction.QUARANTINE),
    // 明确可重试故障已经耗尽Broker重试预算。
    RETRY_EXHAUSTED(SeckillCouponClaimFailureAction.DEAD_LETTER);

    // 当前稳定失败码对应的处置动作。
    private final SeckillCouponClaimFailureAction action;

    /**
     * 创建稳定失败码并绑定唯一处置动作。
     *
     * @param action 该失败类型采用的重试或治理动作
     */
    SeckillCouponClaimFailureCode(SeckillCouponClaimFailureAction action) {
        this.action = action;
    }

    /**
     * 返回当前失败码对应的处置动作。
     *
     * @return 失败处置动作
     */
    public SeckillCouponClaimFailureAction getAction() {
        return action;
    }
}
