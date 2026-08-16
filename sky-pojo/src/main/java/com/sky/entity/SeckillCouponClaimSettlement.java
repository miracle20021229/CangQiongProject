package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀券活动结束后的领取总账结算记录。
 * 无参构造、全参构造及访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponClaimSettlement implements Serializable {

    // 已创建、等待流程6C处理的状态。
    public static final String STATUS_PENDING = "PENDING";
    // 流程6C已经取得处理租约的状态。
    public static final String STATUS_PROCESSING = "PROCESSING";
    // 总账暂未稳定、等待退避复查的状态。
    public static final String STATUS_RECHECK_PENDING = "RECHECK_PENDING";
    // MySQL、Redis与治理记录总账完全一致的终态。
    public static final String STATUS_CONSISTENT = "CONSISTENT";
    // 自动核对停止、需要内部人工介入的状态。
    public static final String STATUS_MANUAL_REQUIRED = "MANUAL_REQUIRED";

    // Java序列化版本号。
    @Serial
    private static final long serialVersionUID = 1L;

    // 数据库自增主键。
    private Long id;
    // 被结算的秒杀券活动ID。
    private Long couponId;
    // 当前活动结算状态。
    private String status;
    // 活动总账累计核对次数。
    private Integer reconcileAttempts;
    // 退避结束后的下次可执行时间。
    private LocalDateTime nextReconcileTime;
    // 当前处理租约的截止时间。
    private LocalDateTime processingDeadline;
    // 当前处理租约的所有权令牌。
    private String processingToken;
    // MySQL记录的活动总库存快照。
    private Integer totalStock;
    // MySQL记录的活动剩余库存快照。
    private Integer mysqlRemainingStock;
    // MySQL用户券领取记录数快照。
    private Long mysqlClaimCount;
    // Redis活动Hash中的剩余库存快照。
    private Long redisRemainingStock;
    // Redis已领取用户集合数量快照。
    private Long redisUserCount;
    // Redis领取流水Hash数量快照。
    private Long redisClaimCount;
    // 该活动尚未解决的失败治理记录数。
    private Long unresolvedFailureCount;
    // 流程6C稳定结论码。
    private String resolutionCode;
    // 本次活动总账核对摘要。
    private String resolutionMessage;
    // 最近一次完成证据读取的时间。
    private LocalDateTime lastCheckedTime;
    // 活动总账确认一致的时间。
    private LocalDateTime settledTime;
    // 记录创建时间。
    private LocalDateTime createTime;
    // 记录最后更新时间。
    private LocalDateTime updateTime;
}
