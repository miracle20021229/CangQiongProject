package com.sky.seckill.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀券领取消息。
 * 流程3-步骤2创建，步骤3～13依靠同一claimId连接Producer、Broker、事务回查和异步Consumer。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponClaimMessage implements Serializable {

    // claimId消息头名称，用于事务回查和失败治理定位领取流水。
    public static final String CLAIM_ID_HEADER = "claimId";
    // couponId消息头名称，用于消息体异常时定位秒杀活动。
    public static final String COUPON_ID_HEADER = "couponId";
    // userId消息头名称，用于消息体异常时定位领取用户。
    public static final String USER_ID_HEADER = "userId";
    // Java序列化版本号。
    private static final long serialVersionUID = 1L;

    // 消息结构版本，用于消费者兼容性校验。
    private Integer schemaVersion;
    // 全链路幂等领取流水号。
    private String claimId;
    // 被领取的秒杀券ID。
    private Long couponId;
    // 发起领取的用户ID。
    private Long userId;
    // Redis预扣发生时的Unix毫秒时间戳。
    private Long claimedAt;

    /**
     * Producer发送前与Consumer消费前共同检查消息结构，版本兼容性由Consumer单独判断。
     */
    public boolean hasRequiredFields() {
        return schemaVersion != null
                && claimId != null
                && !claimId.isBlank()
                && couponId != null
                && userId != null
                && claimedAt != null
                && claimedAt > 0;
    }
}
