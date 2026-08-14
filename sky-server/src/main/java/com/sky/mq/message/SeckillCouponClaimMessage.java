package com.sky.mq.message;

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

    public static final String CLAIM_ID_HEADER = "claimId";
    public static final String COUPON_ID_HEADER = "couponId";
    public static final String USER_ID_HEADER = "userId";
    private static final long serialVersionUID = 1L;

    private Integer schemaVersion;
    private String claimId;
    private Long couponId;
    private Long userId;
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
