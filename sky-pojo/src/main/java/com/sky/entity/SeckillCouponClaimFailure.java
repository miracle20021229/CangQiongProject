package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀券领取最终失败记录，用于流程5隔离、死信追踪与流程6对账。
 * 无参构造、全参构造及访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponClaimFailure implements Serializable {

    // 已隔离，等待人工检查的治理状态。
    public static final String STATUS_QUARANTINED = "QUARANTINED";
    // 已进入流程6A、等待单笔对账的治理状态。
    public static final String STATUS_RECONCILE_PENDING = "RECONCILE_PENDING";
    // 主消费重试耗尽并已进入死信治理的状态。
    public static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
    // 流程6A已经取得处理租约的状态。
    public static final String STATUS_PROCESSING = "PROCESSING";
    // 单笔证据暂不完整、等待退避复查的状态。
    public static final String STATUS_RECHECK_PENDING = "RECHECK_PENDING";
    // 流程6A确认强证据后、等待流程6B修复的状态。
    public static final String STATUS_REPAIR_PENDING = "REPAIR_PENDING";
    // 流程6B已经取得处理租约的状态。
    public static final String STATUS_REPAIRING = "REPAIRING";
    // 自动治理停止、需要内部人工介入的状态。
    public static final String STATUS_MANUAL_REQUIRED = "MANUAL_REQUIRED";
    // 领取事实已经一致并完成治理的终态。
    public static final String STATUS_RESOLVED = "RESOLVED";

    // Java序列化版本号。
    private static final long serialVersionUID = 1L;

    // 数据库自增主键。
    private Long id;
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
    // 流程5选择的失败处置动作。
    private String action;
    // 当前治理状态。
    private String status;
    // 稳定失败分类码。
    private String errorCode;
    // 原始失败摘要。
    private String errorMessage;
    // 原始领取消息正文。
    private String messageBody;
    // 原消息累计投递次数。
    private Integer deliveryAttempts;
    // 流程6A累计对账次数。
    private Integer reconcileAttempts;
    // 流程6B累计修复次数。
    private Integer repairAttempts;
    // 退避结束后的下次可执行时间。
    private LocalDateTime nextReconcileTime;
    // 当前处理租约的截止时间。
    private LocalDateTime processingDeadline;
    // 当前处理租约的所有权令牌。
    private String processingToken;
    // 流程6稳定治理结论码。
    private String resolutionCode;
    // 流程6内部治理结论摘要。
    private String resolutionMessage;
    // 失败事件首次发生时间。
    private LocalDateTime occurredTime;
    // 治理成功结束时间。
    private LocalDateTime resolvedTime;
    // 记录创建时间。
    private LocalDateTime createTime;
    // 记录最后更新时间。
    private LocalDateTime updateTime;
}
