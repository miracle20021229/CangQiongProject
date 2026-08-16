package com.sky.seckill.enums;

/**
 * 流程6稳定结论码，用于治理记录、结构化日志和后续监控聚合。
 */
public enum SeckillCouponClaimResolutionCode {

    // MySQL已经存在与失败事实完全一致的领取记录。
    ALREADY_PERSISTED,
    // Redis领取流水和用户集合均证明本次预扣成立。
    REDIS_EVIDENCE_CONFIRMED,
    // Redis证据暂未读取到，尚处于自动复查窗口。
    EVIDENCE_TEMPORARILY_MISSING,
    // 本次读取MySQL或Redis证据时发生技术异常。
    RECONCILIATION_READ_FAILURE,
    // 失败记录缺少claimId、couponId或userId。
    BUSINESS_IDENTITY_MISSING,
    // 同一claimId已经绑定其他券或用户。
    CLAIM_ID_CONFLICT,
    // 用户已经通过另一条claimId领取该券。
    USER_COUPON_CONFLICT,
    // MySQL中不存在对应秒杀券活动。
    COUPON_NOT_FOUND,
    // Redis领取流水与已领取用户集合相互冲突。
    REDIS_EVIDENCE_CONFLICT,
    // 持久化的原领取消息正文无法安全解析。
    MESSAGE_BODY_INVALID,
    // 原消息正文与失败治理记录中的业务身份不一致。
    MESSAGE_IDENTITY_CONFLICT,
    // 流程6B已经复用原事务完成正向补落库。
    FORWARD_REPAIR_SUCCESS,
    // 流程6B遇到允许有限退避的瞬时技术故障。
    REPAIR_TRANSIENT_FAILURE,
    // 流程6B已经耗尽自动修复次数。
    REPAIR_ATTEMPTS_EXHAUSTED,
    // 流程6B发现不能自动处理的业务冲突。
    REPAIR_BUSINESS_CONFLICT,
    // 修复事务返回后未查到一致的最终领取事实。
    REPAIR_VERIFY_FAILED,
    // 活动结束后的MySQL、Redis和治理总账完全一致。
    ACTIVITY_TOTALS_CONSISTENT,
    // 活动总账暂未一致，仍处于自动复查窗口。
    ACTIVITY_TOTALS_PENDING,
    // 活动总账持续冲突并需要人工处理。
    ACTIVITY_TOTALS_CONFLICT,
    // 活动Redis库存证据缺失，无法完成总账证明。
    ACTIVITY_REDIS_EVIDENCE_MISSING
}
