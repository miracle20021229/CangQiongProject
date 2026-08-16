package com.sky.seckill.mapper;

import com.sky.entity.SeckillCouponClaimFailure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀券领取最终失败记录数据访问接口。
 */
@Mapper
public interface SeckillCouponClaimFailureMapper {

    /**
     * 幂等保存流程5产生的最终失败事实。
     */
    int upsertFinalFailure(SeckillCouponClaimFailure failure);

    /**
     * 按状态和时间窗口查询一批可执行流程6对账的记录。
     */
    List<SeckillCouponClaimFailure> listReconciliationCandidates(
            @Param("now") LocalDateTime now,
            @Param("readyBefore") LocalDateTime readyBefore,
            @Param("leaseExpiredBefore") LocalDateTime leaseExpiredBefore,
            @Param("limit") int limit);

    /**
     * 通过状态CAS抢占一条尚未处理的对账记录，避免多实例重复处理。
     */
    int tryStartPendingReconciliation(@Param("id") Long id,
                                      @Param("expectedStatus") String expectedStatus,
                                      @Param("processingDeadline") LocalDateTime processingDeadline,
                                      @Param("processingToken") String processingToken);

    /**
     * 重新抢占超过处理租约的记录，恢复进程中断留下的PROCESSING状态。
     */
    int tryReclaimExpiredReconciliation(@Param("id") Long id,
                                        @Param("now") LocalDateTime now,
                                        @Param("legacyLeaseExpiredBefore") LocalDateTime legacyLeaseExpiredBefore,
                                        @Param("processingDeadline") LocalDateTime processingDeadline,
                                        @Param("processingToken") String processingToken);

    /**
     * 仅允许当前PROCESSING持有者提交最终对账状态。
     */
    int completeReconciliation(@Param("id") Long id,
                               @Param("targetStatus") String targetStatus,
                               @Param("nextReconcileTime") LocalDateTime nextReconcileTime,
                               @Param("resolutionCode") String resolutionCode,
                               @Param("resolutionMessage") String resolutionMessage,
                               @Param("processingToken") String processingToken);

    /**
     * 查询可执行流程6B受控修复或可从超时租约接管的记录。
     */
    List<SeckillCouponClaimFailure> listRepairCandidates(
            @Param("now") LocalDateTime now,
            @Param("legacyLeaseExpiredBefore") LocalDateTime legacyLeaseExpiredBefore,
            @Param("limit") int limit);

    /**
     * 通过状态CAS把待修复记录抢占为REPAIRING。
     */
    int tryStartPendingRepair(@Param("id") Long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("processingDeadline") LocalDateTime processingDeadline,
                              @Param("processingToken") String processingToken);

    /**
     * 重新接管超过处理租约的REPAIRING记录。
     */
    int tryReclaimExpiredRepair(@Param("id") Long id,
                                @Param("now") LocalDateTime now,
                                @Param("legacyLeaseExpiredBefore") LocalDateTime legacyLeaseExpiredBefore,
                                @Param("processingDeadline") LocalDateTime processingDeadline,
                                @Param("processingToken") String processingToken);

    /**
     * 仅允许当前REPAIRING持有者提交修复结果或退避计划。
     */
    int completeRepair(@Param("id") Long id,
                       @Param("targetStatus") String targetStatus,
                       @Param("nextReconcileTime") LocalDateTime nextReconcileTime,
                       @Param("resolutionCode") String resolutionCode,
                       @Param("resolutionMessage") String resolutionMessage,
                       @Param("processingToken") String processingToken);

    /**
     * 查询当前用户某个领取流水最近的治理状态，不暴露failureId和错误详情。
     */
    SeckillCouponClaimFailure getLatestByClaimIdAndUserId(@Param("claimId") String claimId,
                                                          @Param("userId") Long userId);

    /**
     * 统计指定活动尚未进入RESOLVED终态的治理记录数。
     */
    long countUnresolvedByCouponId(@Param("couponId") Long couponId);
}
