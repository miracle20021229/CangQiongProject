package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.governance.processor.ClaimReconciliationEvaluator.Decision;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.governance.support.ResolutionFormatter;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 流程6A单条处理器，保持租约CAS与结果提交边界清晰可见。
 */
@Component
@Slf4j
public class ClaimReconciliationProcessor {

    private final SeckillCouponClaimFailureMapper failureMapper;
    private final ClaimReconciliationEvaluator evaluator;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimReconciliationProcessor(
            SeckillCouponClaimFailureMapper failureMapper,
            ClaimReconciliationEvaluator evaluator,
            SeckillCouponClaimReconciliationProperties properties) {
        this.failureMapper = failureMapper;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    /**
     * 抢占并处理一条对账记录；未抢到时返回false。
     */
    public boolean process(SeckillCouponClaimFailure failure,
                           LocalDateTime now,
                           LocalDateTime leaseExpiredBefore,
                           Duration processingLease) {
        if (!tryClaim(failure, now, leaseExpiredBefore, processingLease)) {
            return false;
        }

        Decision decision;
        try {
            decision = evaluator.evaluate(failure, now);
        } catch (RuntimeException exception) {
            log.error("[SECKILL_CLAIM_RECONCILIATION_ERROR] 对账读取失败，failureId={}",
                    failure.getFailureId(), exception);
            decision = Decision.recheck(
                    SeckillCouponClaimResolutionCode.RECONCILIATION_READ_FAILURE,
                    ResolutionFormatter.summarize(exception));
        }
        completeSafely(failure, decision, now);
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
        int affectedRows = SeckillCouponClaimFailure.STATUS_PROCESSING.equals(failure.getStatus())
                ? failureMapper.tryReclaimExpiredReconciliation(
                        failure.getId(), now, leaseExpiredBefore, deadline, token)
                : failureMapper.tryStartPendingReconciliation(
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
        LocalDateTime nextTime = resolveNextTime(failure, decision.status(), now);
        try {
            int affectedRows = failureMapper.completeReconciliation(
                    failure.getId(),
                    decision.status(),
                    nextTime,
                    decision.code().name(),
                    decision.message(),
                    failure.getProcessingToken());
            if (affectedRows == 1) {
                log.info("[SECKILL_CLAIM_RECONCILIATION_RESULT] failureId={}，claimId={}，status={}，code={}",
                        failure.getFailureId(), failure.getClaimId(), decision.status(), decision.code());
                if (SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED.equals(decision.status())) {
                    log.error("[SECKILL_CLAIM_MANUAL_ALERT] failureId={}，claimId={}，code={}，message={}",
                            failure.getFailureId(), failure.getClaimId(), decision.code(), decision.message());
                }
                return;
            }
            log.warn("秒杀券领取对账状态提交被跳过，failureId={}，targetStatus={}",
                    failure.getFailureId(), decision.status());
        } catch (RuntimeException exception) {
            log.error("[SECKILL_CLAIM_RECONCILIATION_ERROR] 对账状态提交失败，failureId={}，targetStatus={}",
                    failure.getFailureId(), decision.status(), exception);
        }
    }

    private LocalDateTime resolveNextTime(
            SeckillCouponClaimFailure failure,
            String targetStatus,
            LocalDateTime now) {
        if (SeckillCouponClaimFailure.STATUS_REPAIR_PENDING.equals(targetStatus)) {
            return now;
        }
        if (!SeckillCouponClaimFailure.STATUS_RECHECK_PENDING.equals(targetStatus)) {
            return null;
        }
        Duration backoff = GovernanceTiming.calculateBackoff(
                properties.getRecheckInitialBackoff(),
                properties.getRecheckMaxBackoff(),
                GovernanceTiming.nextAttempt(failure.getReconcileAttempts()),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10));
        return now.plus(backoff);
    }
}
