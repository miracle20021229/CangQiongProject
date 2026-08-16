package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCouponClaimSettlement;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.governance.processor.ClaimSettlementEvaluator.Decision;
import com.sky.seckill.governance.processor.ClaimSettlementEvaluator.Snapshot;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.governance.support.ResolutionFormatter;
import com.sky.seckill.mapper.SeckillCouponClaimSettlementMapper;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 流程6C单活动处理器，负责结算租约、证据清理计划和结果提交。
 */
@Component
@Slf4j
public class ClaimSettlementProcessor {

    private final SeckillCouponClaimSettlementMapper settlementMapper;
    private final ClaimSettlementEvaluator evaluator;
    private final SeckillCouponRedisRepository redisRepository;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimSettlementProcessor(
            SeckillCouponClaimSettlementMapper settlementMapper,
            ClaimSettlementEvaluator evaluator,
            SeckillCouponRedisRepository redisRepository,
            SeckillCouponClaimReconciliationProperties properties) {
        this.settlementMapper = settlementMapper;
        this.evaluator = evaluator;
        this.redisRepository = redisRepository;
        this.properties = properties;
    }

    /**
     * 抢占并结算一个活动；未抢到时返回false。
     */
    public boolean process(SeckillCouponClaimSettlement settlement,
                           LocalDateTime now,
                           LocalDateTime leaseExpiredBefore,
                           Duration processingLease) {
        if (!tryClaim(settlement, now, leaseExpiredBefore, processingLease)) {
            return false;
        }

        Decision decision;
        try {
            decision = evaluator.evaluate(settlement, now);
            if (SeckillCouponClaimSettlement.STATUS_CONSISTENT.equals(decision.status())) {
                Duration retention = GovernanceTiming.positiveOrDefault(
                        properties.getSettledEvidenceRetention(), Duration.ofDays(1));
                redisRepository.scheduleEvidenceCleanup(settlement.getCouponId(), retention);
            }
        } catch (RuntimeException exception) {
            log.error("[SECKILL_CLAIM_SETTLEMENT_ERROR] 活动总账读取失败，couponId={}",
                    settlement.getCouponId(), exception);
            decision = Decision.recheck(
                    SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_PENDING,
                    ResolutionFormatter.summarize(exception),
                    null);
        }
        completeSafely(settlement, decision, now);
        return true;
    }

    private boolean tryClaim(SeckillCouponClaimSettlement settlement,
                             LocalDateTime now,
                             LocalDateTime leaseExpiredBefore,
                             Duration processingLease) {
        if (settlement == null || settlement.getId() == null || settlement.getStatus() == null) {
            return false;
        }

        String token = UUID.randomUUID().toString();
        LocalDateTime deadline = now.plus(processingLease);
        int affectedRows = SeckillCouponClaimSettlement.STATUS_PROCESSING.equals(settlement.getStatus())
                ? settlementMapper.tryReclaimExpired(
                        settlement.getId(), now, leaseExpiredBefore, deadline, token)
                : settlementMapper.tryStart(
                        settlement.getId(), settlement.getStatus(), deadline, token);
        if (affectedRows == 1) {
            settlement.setProcessingToken(token);
            return true;
        }
        return false;
    }

    private void completeSafely(
            SeckillCouponClaimSettlement settlement,
            Decision decision,
            LocalDateTime now) {
        settlement.setStatus(decision.status());
        settlement.setResolutionCode(decision.code().name());
        settlement.setResolutionMessage(ResolutionFormatter.truncate(decision.message()));
        settlement.setNextReconcileTime(nextReconcileTime(settlement, decision, now));
        Snapshot snapshot = decision.snapshot();
        if (snapshot != null) {
            snapshot.applyTo(settlement);
        }

        try {
            int affectedRows = settlementMapper.complete(settlement);
            if (affectedRows == 1) {
                log.info("[SECKILL_CLAIM_SETTLEMENT_RESULT] couponId={}，status={}，code={}，message={}",
                        settlement.getCouponId(), decision.status(), decision.code(), decision.message());
                if (SeckillCouponClaimSettlement.STATUS_MANUAL_REQUIRED.equals(decision.status())) {
                    log.error("[SECKILL_CLAIM_SETTLEMENT_ALERT] couponId={}，code={}，message={}",
                            settlement.getCouponId(), decision.code(), decision.message());
                }
                return;
            }
            log.warn("秒杀券活动结算状态提交被跳过，couponId={}，targetStatus={}",
                    settlement.getCouponId(), decision.status());
        } catch (RuntimeException exception) {
            log.error("[SECKILL_CLAIM_SETTLEMENT_ERROR] 活动结算状态提交失败，couponId={}，targetStatus={}",
                    settlement.getCouponId(), decision.status(), exception);
        }
    }

    private LocalDateTime nextReconcileTime(
            SeckillCouponClaimSettlement settlement,
            Decision decision,
            LocalDateTime now) {
        if (!SeckillCouponClaimSettlement.STATUS_RECHECK_PENDING.equals(decision.status())) {
            return null;
        }
        Duration backoff = GovernanceTiming.calculateBackoff(
                properties.getSettlementInitialBackoff(),
                properties.getSettlementMaxBackoff(),
                GovernanceTiming.nextAttempt(settlement.getReconcileAttempts()),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15));
        return now.plus(backoff);
    }
}
