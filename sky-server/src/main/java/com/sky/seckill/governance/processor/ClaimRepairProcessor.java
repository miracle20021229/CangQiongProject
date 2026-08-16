package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.governance.processor.ClaimRepairExecutor.Decision;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.governance.support.ResolutionFormatter;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 流程6B单条处理器，负责修复租约和治理状态提交。
 */
@Component
@Slf4j
public class ClaimRepairProcessor {

    private final SeckillCouponClaimFailureMapper failureMapper;
    private final ClaimRepairExecutor executor;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimRepairProcessor(
            SeckillCouponClaimFailureMapper failureMapper,
            ClaimRepairExecutor executor,
            SeckillCouponClaimReconciliationProperties properties) {
        this.failureMapper = failureMapper;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * 抢占并修复一条记录；未抢到时返回false。
     */
    public boolean process(SeckillCouponClaimFailure failure,
                           LocalDateTime now,
                           LocalDateTime leaseExpiredBefore,
                           Duration processingLease) {
        if (!tryClaim(failure, now, leaseExpiredBefore, processingLease)) {
            return false;
        }
        completeSafely(failure, executor.execute(failure), now);
        return true;
    }

    private boolean tryClaim(SeckillCouponClaimFailure failure,
                             LocalDateTime now,
                             LocalDateTime leaseExpiredBefore,
                             Duration processingLease) {
        if (failure == null || failure.getId() == null || failure.getStatus() == null) {
            return false;
        }

        String token = UUID.randomUUID().toString();
        LocalDateTime deadline = now.plus(processingLease);
        int affectedRows = SeckillCouponClaimFailure.STATUS_REPAIRING.equals(failure.getStatus())
                ? failureMapper.tryReclaimExpiredRepair(
                        failure.getId(), now, leaseExpiredBefore, deadline, token)
                : failureMapper.tryStartPendingRepair(
                        failure.getId(), failure.getStatus(), deadline, token);
        if (affectedRows == 1) {
            failure.setProcessingToken(token);
            return true;
        }
        return false;
    }

    private void completeSafely(
            SeckillCouponClaimFailure failure,
            Decision decision,
            LocalDateTime now) {
        LocalDateTime nextTime = decision.retry() ? nextRepairTime(failure, now) : null;
        try {
            int affectedRows = failureMapper.completeRepair(
                    failure.getId(),
                    decision.status(),
                    nextTime,
                    decision.code().name(),
                    ResolutionFormatter.truncate(decision.message()),
                    failure.getProcessingToken());
            if (affectedRows == 1) {
                log.info("[SECKILL_CLAIM_REPAIR_RESULT] failureId={}，claimId={}，status={}，code={}",
                        failure.getFailureId(), failure.getClaimId(), decision.status(), decision.code());
                if (SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED.equals(decision.status())) {
                    log.error("[SECKILL_CLAIM_MANUAL_ALERT] failureId={}，claimId={}，code={}，message={}",
                            failure.getFailureId(), failure.getClaimId(), decision.code(), decision.message());
                }
                return;
            }
            log.warn("秒杀券领取修复状态提交被跳过，failureId={}，targetStatus={}",
                    failure.getFailureId(), decision.status());
        } catch (RuntimeException exception) {
            log.error("[SECKILL_CLAIM_REPAIR_ERROR] 修复状态提交失败，failureId={}，targetStatus={}",
                    failure.getFailureId(), decision.status(), exception);
        }
    }

    private LocalDateTime nextRepairTime(SeckillCouponClaimFailure failure, LocalDateTime now) {
        Duration backoff = GovernanceTiming.calculateBackoff(
                properties.getRepairInitialBackoff(),
                properties.getRepairMaxBackoff(),
                GovernanceTiming.nextAttempt(failure.getRepairAttempts()),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10));
        return now.plus(backoff);
    }
}
