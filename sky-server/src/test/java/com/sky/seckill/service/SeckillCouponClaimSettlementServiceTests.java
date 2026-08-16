package com.sky.seckill.service;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimSettlement;
import com.sky.seckill.governance.processor.ClaimSettlementEvaluator;
import com.sky.seckill.governance.processor.ClaimSettlementProcessor;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mapper.SeckillCouponClaimSettlementMapper;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import com.sky.seckill.redis.SeckillCouponRedisRepository.SettlementEvidenceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证流程6C核对MySQL、Redis和治理记录总账且不自动改写库存。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimSettlementServiceTests {

    // 模拟活动结算状态机数据访问接口。
    @Mock
    private SeckillCouponClaimSettlementMapper settlementMapper;
    // 模拟未解决治理记录统计接口。
    @Mock
    private SeckillCouponClaimFailureMapper failureMapper;
    // 模拟秒杀券活动和库存事实数据访问接口。
    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    // 模拟MySQL用户券数量统计接口。
    @Mock
    private UserCouponMapper userCouponMapper;
    // 模拟Redis原子总账证据仓储。
    @Mock
    private SeckillCouponRedisRepository redisRepository;

    // 被测的流程6C活动结算服务。
    private SeckillCouponClaimSettlementService settlementService;

    /**
     * 为每个测试创建固定安全窗口和人工阈值的活动结算服务。
     */
    @BeforeEach
    void setUp() {
        SeckillCouponClaimReconciliationProperties properties =
                new SeckillCouponClaimReconciliationProperties();
        properties.setSettlementReadyDelay(Duration.ofMinutes(10));
        properties.setSettlementManualAfter(Duration.ofHours(2));
        ClaimSettlementEvaluator evaluator = new ClaimSettlementEvaluator(
                failureMapper,
                seckillCouponMapper,
                userCouponMapper,
                redisRepository,
                properties
        );
        ClaimSettlementProcessor processor = new ClaimSettlementProcessor(
                settlementMapper,
                evaluator,
                redisRepository,
                properties
        );
        settlementService = new SeckillCouponClaimSettlementService(
                settlementMapper,
                processor,
                properties
        );
    }

    /**
     * 六项总账全部一致时应持久化CONSISTENT并安排Redis证据延迟清理。
     */
    @Test
    void shouldSettleWhenMysqlRedisAndFailureTotalsAreConsistent() {
        SeckillCouponClaimSettlement candidate = pendingSettlement();
        prepareCandidate(candidate);
        when(seckillCouponMapper.getById(801L)).thenReturn(couponEndedMinutesAgo(30));
        when(userCouponMapper.countByCouponId(801L)).thenReturn(2L);
        when(failureMapper.countUnresolvedByCouponId(801L)).thenReturn(0L);
        when(redisRepository.readSettlementEvidence(801L))
                .thenReturn(new SettlementEvidenceSnapshot(8L, 2L, 2L));

        int processedCount = settlementService.settleBatch();

        assertEquals(1, processedCount);
        verify(redisRepository).scheduleEvidenceCleanup(eq(801L), any(Duration.class));
        ArgumentCaptor<SeckillCouponClaimSettlement> captor =
                ArgumentCaptor.forClass(SeckillCouponClaimSettlement.class);
        verify(settlementMapper).complete(captor.capture());
        assertEquals(SeckillCouponClaimSettlement.STATUS_CONSISTENT, captor.getValue().getStatus());
        assertEquals(SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_CONSISTENT.name(),
                captor.getValue().getResolutionCode());
        assertEquals(2L, captor.getValue().getMysqlClaimCount());
    }

    /**
     * 安全窗口后总账仍可能在途时应退避复查且保留全部Redis证据。
     */
    @Test
    void shouldRecheckRecentMismatchWithoutChangingInventory() {
        SeckillCouponClaimSettlement candidate = pendingSettlement();
        prepareCandidate(candidate);
        when(seckillCouponMapper.getById(801L)).thenReturn(couponEndedMinutesAgo(30));
        when(userCouponMapper.countByCouponId(801L)).thenReturn(1L);
        when(failureMapper.countUnresolvedByCouponId(801L)).thenReturn(1L);
        when(redisRepository.readSettlementEvidence(801L))
                .thenReturn(new SettlementEvidenceSnapshot(8L, 2L, 2L));

        settlementService.settleBatch();

        ArgumentCaptor<SeckillCouponClaimSettlement> captor =
                ArgumentCaptor.forClass(SeckillCouponClaimSettlement.class);
        verify(settlementMapper).complete(captor.capture());
        assertEquals(SeckillCouponClaimSettlement.STATUS_RECHECK_PENDING,
                captor.getValue().getStatus());
        assertNotNull(captor.getValue().getNextReconcileTime());
        verify(redisRepository, never()).scheduleEvidenceCleanup(any(), any());
    }

    /**
     * 总账冲突超过人工阈值后应停止自动轮询并进入活动级人工治理。
     */
    @Test
    void shouldRequireManualReviewForLongLivedMismatch() {
        SeckillCouponClaimSettlement candidate = pendingSettlement();
        prepareCandidate(candidate);
        when(seckillCouponMapper.getById(801L)).thenReturn(couponEndedHoursAgo(3));
        when(userCouponMapper.countByCouponId(801L)).thenReturn(1L);
        when(failureMapper.countUnresolvedByCouponId(801L)).thenReturn(1L);
        when(redisRepository.readSettlementEvidence(801L))
                .thenReturn(new SettlementEvidenceSnapshot(8L, 2L, 2L));

        settlementService.settleBatch();

        ArgumentCaptor<SeckillCouponClaimSettlement> captor =
                ArgumentCaptor.forClass(SeckillCouponClaimSettlement.class);
        verify(settlementMapper).complete(captor.capture());
        assertEquals(SeckillCouponClaimSettlement.STATUS_MANUAL_REQUIRED,
                captor.getValue().getStatus());
        assertEquals(SeckillCouponClaimResolutionCode.ACTIVITY_TOTALS_CONFLICT.name(),
                captor.getValue().getResolutionCode());
        verify(redisRepository, never()).scheduleEvidenceCleanup(any(), any());
    }

    /**
     * CAS抢占失败时不得读取活动总账或安排证据清理。
     */
    @Test
    void shouldSkipSettlementWhenAnotherInstanceWinsCas() {
        SeckillCouponClaimSettlement candidate = pendingSettlement();
        when(settlementMapper.listCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(candidate));
        when(settlementMapper.tryStart(
                eq(candidate.getId()), eq(candidate.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(0);

        int processedCount = settlementService.settleBatch();

        assertEquals(0, processedCount);
        verify(seckillCouponMapper, never()).getById(any());
        verify(settlementMapper, never()).complete(any());
    }

    /**
     * 准备一个可成功CAS抢占并提交结算结果的候选记录。
     */
    private void prepareCandidate(SeckillCouponClaimSettlement candidate) {
        when(settlementMapper.listCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(candidate));
        when(settlementMapper.tryStart(
                eq(candidate.getId()), eq(candidate.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(settlementMapper.complete(any(SeckillCouponClaimSettlement.class))).thenReturn(1);
    }

    /**
     * 构造一条券维度待结算记录。
     */
    private SeckillCouponClaimSettlement pendingSettlement() {
        return SeckillCouponClaimSettlement.builder()
                .id(800L)
                .couponId(801L)
                .status(SeckillCouponClaimSettlement.STATUS_PENDING)
                .reconcileAttempts(0)
                .build();
    }

    /**
     * 构造结束指定分钟数且MySQL总账内部一致的活动。
     */
    private SeckillCoupon couponEndedMinutesAgo(long minutes) {
        return SeckillCoupon.builder()
                .id(801L)
                .totalStock(10)
                .remainingStock(8)
                .claimEndTime(LocalDateTime.now().minusMinutes(minutes))
                .build();
    }

    /**
     * 构造结束指定小时数的活动，用于验证人工治理阈值。
     */
    private SeckillCoupon couponEndedHoursAgo(long hours) {
        return SeckillCoupon.builder()
                .id(801L)
                .totalStock(10)
                .remainingStock(8)
                .claimEndTime(LocalDateTime.now().minusHours(hours))
                .build();
    }
}
