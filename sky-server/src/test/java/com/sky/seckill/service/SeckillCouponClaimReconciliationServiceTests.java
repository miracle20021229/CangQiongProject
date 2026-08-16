package com.sky.seckill.service;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.entity.UserCoupon;
import com.sky.seckill.governance.processor.ClaimReconciliationEvaluator;
import com.sky.seckill.governance.processor.ClaimReconciliationProcessor;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证流程6A基于MySQL最终事实和Redis预扣证据推进单笔治理状态。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimReconciliationServiceTests {

    // 模拟失败治理状态机数据访问接口。
    @Mock
    private SeckillCouponClaimFailureMapper failureMapper;
    // 模拟MySQL用户券最终事实数据访问接口。
    @Mock
    private UserCouponMapper userCouponMapper;
    // 模拟秒杀券活动事实数据访问接口。
    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    // 模拟Redis预扣证据仓储。
    @Mock
    private SeckillCouponRedisRepository redisRepository;

    // 被测的流程6A单笔对账服务。
    private SeckillCouponClaimReconciliationService reconciliationService;

    /**
     * 为每个测试创建固定对账参数和独立服务实例。
     */
    @BeforeEach
    void setUp() {
        SeckillCouponClaimReconciliationProperties properties =
                new SeckillCouponClaimReconciliationProperties();
        properties.setBatchSize(20);
        properties.setReadyDelay(Duration.ofSeconds(30));
        properties.setProcessingLease(Duration.ofMinutes(2));
        properties.setManualAfter(Duration.ofMinutes(30));
        SeckillCouponClaimEvidenceInspector evidenceInspector =
                new SeckillCouponClaimEvidenceInspector(
                        userCouponMapper, seckillCouponMapper, redisRepository);
        ClaimReconciliationEvaluator evaluator =
                new ClaimReconciliationEvaluator(evidenceInspector, properties);
        ClaimReconciliationProcessor processor =
                new ClaimReconciliationProcessor(failureMapper, evaluator, properties);
        reconciliationService = new SeckillCouponClaimReconciliationService(
                failureMapper, processor, properties);
    }

    /**
     * MySQL已存在完全一致领取事实时，应关闭失败记录且不读取Redis。
     */
    @Test
    void shouldResolveWhenMysqlAlreadyContainsExactClaim() {
        SeckillCouponClaimFailure failure = pendingFailure();
        prepareCandidate(failure);
        when(userCouponMapper.getByClaimId("claim-601")).thenReturn(UserCoupon.builder()
                .claimId("claim-601")
                .couponId(601L)
                .userId(602L)
                .build());

        int processedCount = reconciliationService.reconcileBatch();

        assertEquals(1, processedCount);
        verify(failureMapper).completeReconciliation(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_RESOLVED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.ALREADY_PERSISTED.name()),
                anyString(),
                anyString());
        verify(redisRepository, never()).findClaimOwner(any(), any());
    }

    /**
     * Redis两项预扣证据完整但MySQL缺失时，应进入流程6B受控修复。
     */
    @Test
    void shouldMarkRepairPendingWhenRedisEvidenceIsComplete() {
        SeckillCouponClaimFailure failure = pendingFailure();
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(601L)).thenReturn(SeckillCoupon.builder().id(601L).build());
        when(redisRepository.findClaimOwner(601L, "claim-601")).thenReturn("602");
        when(redisRepository.isUserClaimed(601L, 602L)).thenReturn(true);

        reconciliationService.reconcileBatch();

        verify(failureMapper).completeReconciliation(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_REPAIR_PENDING),
                any(LocalDateTime.class),
                eq(SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFIRMED.name()),
                anyString(),
                anyString());
    }

    /**
     * claimId已对应其他业务身份时，应停止自动处理并转人工治理。
     */
    @Test
    void shouldRequireManualReviewWhenClaimIdConflicts() {
        SeckillCouponClaimFailure failure = pendingFailure();
        prepareCandidate(failure);
        when(userCouponMapper.getByClaimId("claim-601")).thenReturn(UserCoupon.builder()
                .claimId("claim-601")
                .couponId(999L)
                .userId(602L)
                .build());

        reconciliationService.reconcileBatch();

        verify(failureMapper).completeReconciliation(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.CLAIM_ID_CONFLICT.name()),
                anyString(),
                anyString());
    }

    /**
     * Redis证据短暂缺失时应写入下一次退避时间。
     */
    @Test
    void shouldRecheckWhenRedisEvidenceIsRecentlyMissing() {
        SeckillCouponClaimFailure failure = pendingFailure();
        failure.setOccurredTime(LocalDateTime.now().minusMinutes(5));
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(601L)).thenReturn(SeckillCoupon.builder().id(601L).build());

        reconciliationService.reconcileBatch();

        verify(failureMapper).completeReconciliation(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_RECHECK_PENDING),
                any(LocalDateTime.class),
                eq(SeckillCouponClaimResolutionCode.EVIDENCE_TEMPORARILY_MISSING.name()),
                anyString(),
                anyString());
    }

    /**
     * Redis证据长期缺失时应停止自动轮询并转人工治理。
     */
    @Test
    void shouldRequireManualReviewWhenEvidenceRemainsMissing() {
        SeckillCouponClaimFailure failure = pendingFailure();
        failure.setOccurredTime(LocalDateTime.now().minusHours(1));
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(601L)).thenReturn(SeckillCoupon.builder().id(601L).build());

        reconciliationService.reconcileBatch();

        verify(failureMapper).completeReconciliation(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.REDIS_EVIDENCE_CONFLICT.name()),
                anyString(),
                anyString());
    }

    /**
     * CAS抢占失败说明其他实例正在处理，本实例必须跳过该记录。
     */
    @Test
    void shouldSkipCandidateWhenAnotherInstanceWinsCas() {
        SeckillCouponClaimFailure failure = pendingFailure();
        when(failureMapper.listReconciliationCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(failure));
        when(failureMapper.tryStartPendingReconciliation(
                eq(failure.getId()), eq(failure.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(0);

        int processedCount = reconciliationService.reconcileBatch();

        assertEquals(0, processedCount);
        verify(userCouponMapper, never()).getByClaimId(any());
        verify(failureMapper, never()).completeReconciliation(
                any(), any(), any(), any(), any(), any());
    }

    /**
     * 准备一个可被当前实例成功CAS抢占的候选记录。
     */
    private void prepareCandidate(SeckillCouponClaimFailure failure) {
        when(failureMapper.listReconciliationCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(failure));
        when(failureMapper.tryStartPendingReconciliation(
                eq(failure.getId()), eq(failure.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(failureMapper.completeReconciliation(
                eq(failure.getId()), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    /**
     * 构造具备完整业务身份的待对账失败事实。
     */
    private SeckillCouponClaimFailure pendingFailure() {
        return SeckillCouponClaimFailure.builder()
                .id(600L)
                .failureId("failure-600")
                .claimId("claim-601")
                .couponId(601L)
                .userId(602L)
                .status(SeckillCouponClaimFailure.STATUS_RECONCILE_PENDING)
                .reconcileAttempts(0)
                .occurredTime(LocalDateTime.now().minusMinutes(5))
                .updateTime(LocalDateTime.now().minusMinutes(1))
                .build();
    }
}
