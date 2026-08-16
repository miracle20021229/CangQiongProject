package com.sky.seckill.mq.message;

import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流程5领取失败治理消息。
 * 主消费者只将最终隔离、待对账和死信事实发往独立 Topic。
 * 无参构造、全参构造及访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponClaimFailureMessage implements Serializable {

    // Java序列化版本号。
    private static final long serialVersionUID = 1L;

    // 失败治理消息结构版本。
    private Integer schemaVersion;
    // 失败治理事件的全局幂等ID。
    private String failureId;
    // 原始RocketMQ消息ID。
    private String sourceMessageId;
    // 原始RocketMQ Topic。
    private String sourceTopic;
    // 秒杀券领取流水ID。
    private String claimId;
    // 秒杀券活动ID。
    private Long couponId;
    // 领取用户ID。
    private Long userId;
    // 流程5选定的失败处置动作。
    private SeckillCouponClaimFailureAction action;
    // 决定重试、隔离或对账路由的稳定失败码。
    private SeckillCouponClaimFailureCode errorCode;
    // 供治理审计使用的异常摘要。
    private String errorMessage;
    // 未修改的原领取消息正文。
    private String messageBody;
    // 原消息累计投递次数。
    private Integer deliveryAttempts;
    // 失败治理事件发生的Epoch毫秒时间戳。
    private Long occurredAt;

    /**
     * 校验失败治理消息是否具备落库和幂等处理所需的最小字段。
     *
     * @return 字段完整且失败码与处置动作匹配时返回true
     */
    public boolean hasRequiredFields() {
        return schemaVersion != null
                && failureId != null
                && !failureId.isBlank()
                && action != null
                && action.requiresFailureRouting()
                && errorCode != null
                && errorCode.getAction() == action
                && deliveryAttempts != null
                && deliveryAttempts > 0
                && occurredAt != null
                && occurredAt > 0;
    }
}
