package com.sky.seckill.mapper;

import com.sky.entity.SeckillCouponClaimSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀券活动结束总账结算数据访问接口。
 */
@Mapper
public interface SeckillCouponClaimSettlementMapper {

    /**
     * 为已超过安全窗口且尚无结算记录的活动幂等创建待结算任务。
     */
    int createPendingForEndedCoupons(@Param("readyBefore") LocalDateTime readyBefore,
                                     @Param("limit") int limit);

    /**
     * 查询到期的待结算记录和租约超时记录。
     */
    List<SeckillCouponClaimSettlement> listCandidates(
            @Param("now") LocalDateTime now,
            @Param("legacyLeaseExpiredBefore") LocalDateTime legacyLeaseExpiredBefore,
            @Param("limit") int limit);

    /**
     * 通过状态CAS抢占一条待结算记录。
     */
    int tryStart(@Param("id") Long id,
                 @Param("expectedStatus") String expectedStatus,
                 @Param("processingDeadline") LocalDateTime processingDeadline,
                 @Param("processingToken") String processingToken);

    /**
     * 重新接管超过处理租约的结算记录。
     */
    int tryReclaimExpired(@Param("id") Long id,
                          @Param("now") LocalDateTime now,
                          @Param("legacyLeaseExpiredBefore") LocalDateTime legacyLeaseExpiredBefore,
                          @Param("processingDeadline") LocalDateTime processingDeadline,
                          @Param("processingToken") String processingToken);

    /**
     * 仅允许当前PROCESSING持有者提交总账快照与结算结果。
     */
    int complete(@Param("settlement") SeckillCouponClaimSettlement settlement);
}
