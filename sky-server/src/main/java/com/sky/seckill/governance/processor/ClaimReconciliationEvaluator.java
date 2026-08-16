package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.MysqlEvidence;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.MysqlEvidenceState;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.RedisEvidenceState;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 流程6A证据判定器，只读取事实并生成结构化结论。
 */
@Component
public class ClaimReconciliationEvaluator {

    private final SeckillCouponClaimEvidenceInspector evidenceInspector;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimReconciliationEvaluator(
            SeckillCouponClaimEvidenceInspector evidenceInspector,
            SeckillCouponClaimReconciliationProperties properties) {
        this.evidenceInspector = evidenceInspector;
        this.properties = properties;
    }

    /**
     * MySQL最终事实优先，Redis预扣证据用于决定是否允许修复。
     */
    Decision evaluate(SeckillCouponClaimFailure failure, LocalDateTime now) {
        MysqlEvidence mysqlEvidence = evidenceInspector.inspectMysqlFacts(failure);
        if (mysqlEvidence.state() == MysqlEvidenceState.ALREADY_PERSISTED) {
            return Decision.resolved(mysqlEvidence.code(), mysqlEvidence.message());
        }
        if (mysqlEvidence.state() != MysqlEvidenceState.READY) {
            return Decision.manual(mysqlEvidence.code(), mysqlEvidence.message());
        }

        RedisEvidenceState redisEvidence = evidenceInspector.inspectRedisEvidence(failure);
        if (redisEvidence == RedisEvidenceState.CONFIRMED) {
            return Decision.repair(
                    SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFIRMED,
                    "Redis claim流水与用户集合证据完整，允许进入受控修复");
        }
        if (redisEvidence == RedisEvidenceState.CONFLICT) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFLICT,
                    "Redis claim流水与用户集合证据不一致");
        }

        if (hasExceededManualThreshold(failure, now)) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFLICT,
                    "Redis预扣证据持续缺失并已超过自动复查时限");
        }
        return Decision.recheck(
                SeckillCouponClaimResolutionCode.EVIDENCE_TEMPORARILY_MISSING,
                "Redis预扣证据暂未读取到，等待退避后复查");
    }

    private boolean hasExceededManualThreshold(
            SeckillCouponClaimFailure failure,
            LocalDateTime now) {
        if (failure.getOccurredTime() == null) {
            return true;
        }
        Duration manualAfter = GovernanceTiming.positiveOrDefault(
                properties.getManualAfter(), Duration.ofMinutes(30));
        return !failure.getOccurredTime().isAfter(now.minus(manualAfter));
    }

    /**
     * 流程6A单条证据判定结果。
     */
    record Decision(
            String status,
            SeckillCouponClaimResolutionCode code,
            String message) {

        static Decision resolved(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_RESOLVED, code, message);
        }

        static Decision repair(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_REPAIR_PENDING, code, message);
        }

        static Decision recheck(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_RECHECK_PENDING, code, message);
        }

        static Decision manual(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED, code, message);
        }
    }
}
