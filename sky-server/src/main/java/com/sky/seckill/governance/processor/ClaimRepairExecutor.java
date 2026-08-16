package com.sky.seckill.governance.processor;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.entity.UserCoupon;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.governance.support.ClaimMessageInspector;
import com.sky.seckill.governance.support.ClaimMessageInspector.MessageEvidence;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.governance.support.ResolutionFormatter;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.MysqlEvidence;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.MysqlEvidenceState;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector.RedisEvidenceState;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.policy.SeckillCouponClaimRetryPolicy;
import com.sky.seckill.service.SeckillCouponClaimPersistenceService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 流程6B修复执行器，集中负责强证据重验、补落库和异常分类。
 */
@Component
public class ClaimRepairExecutor {

    private final UserCouponMapper userCouponMapper;
    private final SeckillCouponClaimEvidenceInspector evidenceInspector;
    private final SeckillCouponClaimPersistenceService persistenceService;
    private final SeckillCouponClaimRetryPolicy retryPolicy;
    private final ClaimMessageInspector messageInspector;
    private final SeckillCouponClaimReconciliationProperties properties;

    public ClaimRepairExecutor(
            UserCouponMapper userCouponMapper,
            SeckillCouponClaimEvidenceInspector evidenceInspector,
            SeckillCouponClaimPersistenceService persistenceService,
            SeckillCouponClaimRetryPolicy retryPolicy,
            ClaimMessageInspector messageInspector,
            SeckillCouponClaimReconciliationProperties properties) {
        this.userCouponMapper = userCouponMapper;
        this.evidenceInspector = evidenceInspector;
        this.persistenceService = persistenceService;
        this.retryPolicy = retryPolicy;
        this.messageInspector = messageInspector;
        this.properties = properties;
    }

    /**
     * 执行一次受控修复，并把所有可预期故障转换为治理结论。
     */
    Decision execute(SeckillCouponClaimFailure failure) {
        try {
            return persistAfterValidation(failure);
        } catch (DuplicateKeyException exception) {
            return resolveDuplicateRace(failure, exception);
        } catch (SeckillCouponClaimPersistenceException exception) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REPAIR_BUSINESS_CONFLICT,
                    exception.getFailureCode() + ": " + exception.getMessage());
        } catch (DataAccessException exception) {
            return classifyDataAccessFailure(failure, exception);
        } catch (RuntimeException exception) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REPAIR_BUSINESS_CONFLICT,
                    ResolutionFormatter.summarize(exception));
        }
    }

    /**
     * 全部强证据一致后才复用流程4事务，并在提交后二次确认。
     */
    private Decision persistAfterValidation(SeckillCouponClaimFailure failure) {
        MysqlEvidence mysqlEvidence = evidenceInspector.inspectMysqlFacts(failure);
        if (mysqlEvidence.state() == MysqlEvidenceState.ALREADY_PERSISTED) {
            return Decision.resolved(mysqlEvidence.code(), mysqlEvidence.message());
        }
        if (mysqlEvidence.state() != MysqlEvidenceState.READY) {
            return Decision.manual(mysqlEvidence.code(), mysqlEvidence.message());
        }

        SeckillCoupon coupon = mysqlEvidence.coupon();
        if (coupon.getClaimEndTime() == null) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.COUPON_NOT_FOUND,
                    "秒杀券领取结束时间缺失");
        }

        MessageEvidence messageEvidence = messageInspector.inspect(failure);
        if (messageEvidence.code() != null) {
            return Decision.manual(messageEvidence.code(), messageEvidence.detail());
        }
        SeckillCouponClaimMessage message = messageEvidence.message();

        RedisEvidenceState redisEvidence = evidenceInspector.inspectRedisEvidence(failure);
        if (redisEvidence != RedisEvidenceState.CONFIRMED) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFLICT,
                    "执行修复前Redis强证据已缺失或发生冲突");
        }

        persistenceService.persist(message);
        UserCoupon verified = userCouponMapper.getByClaimId(failure.getClaimId());
        if (!evidenceInspector.matches(verified, failure)) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REPAIR_VERIFY_FAILED,
                    "落库事务返回后未查询到完全一致的领取记录");
        }
        return Decision.resolved(
                SeckillCouponClaimResolutionCode.FORWARD_REPAIR_SUCCESS,
                "正向补落库成功并已完成claimId二次核验");
    }

    private Decision resolveDuplicateRace(
            SeckillCouponClaimFailure failure,
            DuplicateKeyException exception) {
        UserCoupon persisted = userCouponMapper.getByClaimId(failure.getClaimId());
        if (evidenceInspector.matches(persisted, failure)) {
            return Decision.resolved(
                    SeckillCouponClaimResolutionCode.ALREADY_PERSISTED,
                    "并发修复已由其他事务完成，当前记录按幂等成功关闭");
        }
        return Decision.manual(
                SeckillCouponClaimResolutionCode.REPAIR_BUSINESS_CONFLICT,
                ResolutionFormatter.summarize(exception));
    }

    private Decision classifyDataAccessFailure(
            SeckillCouponClaimFailure failure,
            DataAccessException exception) {
        SeckillCouponClaimFailureCode failureCode = retryPolicy.classify(exception);
        if (failureCode.getAction() != SeckillCouponClaimFailureAction.RETRY) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REPAIR_BUSINESS_CONFLICT,
                    failureCode + ": " + ResolutionFormatter.summarize(exception));
        }

        int currentAttempt = GovernanceTiming.nextAttempt(failure.getRepairAttempts());
        int maxAttempts = Math.max(properties.getMaxRepairAttempts(), 1);
        if (currentAttempt >= maxAttempts) {
            return Decision.manual(
                    SeckillCouponClaimResolutionCode.REPAIR_ATTEMPTS_EXHAUSTED,
                    "瞬时数据库故障已耗尽受控修复次数，attempt=" + currentAttempt);
        }
        return Decision.retry(
                SeckillCouponClaimResolutionCode.REPAIR_TRANSIENT_FAILURE,
                failureCode + ": " + ResolutionFormatter.summarize(exception));
    }

    /**
     * 流程6B单条修复结果。
     */
    record Decision(
            String status,
            SeckillCouponClaimResolutionCode code,
            String message,
            boolean retry) {

        static Decision resolved(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_RESOLVED, code, message, false);
        }

        static Decision retry(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_REPAIR_PENDING, code, message, true);
        }

        static Decision manual(SeckillCouponClaimResolutionCode code, String message) {
            return new Decision(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED, code, message, false);
        }
    }
}
