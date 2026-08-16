package com.sky.seckill.service;

import com.sky.seckill.service.SeckillCouponClaimPersistenceService;
import com.sky.entity.SeckillCoupon;
import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.entity.UserCoupon;
import com.sky.seckill.governance.processor.ClaimRepairExecutor;
import com.sky.seckill.governance.processor.ClaimRepairProcessor;
import com.sky.seckill.governance.support.ClaimMessageInspector;
import com.sky.seckill.governance.support.SeckillCouponClaimEvidenceInspector;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.policy.SeckillCouponClaimRetryPolicy;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证流程6B只有在强证据一致时复用原事务完成有限正向修复。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimRepairServiceTests {

    // 模拟失败治理修复状态机数据访问接口。
    @Mock
    private SeckillCouponClaimFailureMapper failureMapper;
    // 模拟MySQL用户券最终事实数据访问接口。
    @Mock
    private UserCouponMapper userCouponMapper;
    // 模拟秒杀券活动事实数据访问接口。
    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    // 模拟Redis领取强证据仓储。
    @Mock
    private SeckillCouponRedisRepository redisRepository;
    // 模拟流程4幂等领取落库事务服务。
    @Mock
    private SeckillCouponClaimPersistenceService persistenceService;

    // 被测的流程6B受控修复服务。
    private SeckillCouponClaimRepairService repairService;

    /**
     * 为每个测试创建启用有限修复次数的服务实例。
     */
    @BeforeEach
    void setUp() {
        SeckillCouponClaimReconciliationProperties properties =
                new SeckillCouponClaimReconciliationProperties();
        properties.setMaxRepairAttempts(3);
        SeckillCouponClaimEvidenceInspector evidenceInspector =
                new SeckillCouponClaimEvidenceInspector(
                        userCouponMapper, seckillCouponMapper, redisRepository);
        ClaimMessageInspector messageInspector =
                new ClaimMessageInspector(new SeckillCouponClaimMessageCodec());
        ClaimRepairExecutor executor = new ClaimRepairExecutor(
                userCouponMapper,
                evidenceInspector,
                persistenceService,
                new SeckillCouponClaimRetryPolicy(),
                messageInspector,
                properties
        );
        ClaimRepairProcessor processor =
                new ClaimRepairProcessor(failureMapper, executor, properties);
        repairService = new SeckillCouponClaimRepairService(
                failureMapper,
                processor,
                properties
        );
    }

    /**
     * 四方身份和Redis强证据一致时应调用原事务，并在二次确认后关闭记录。
     */
    @Test
    void shouldRepairAndVerifyExactClaim() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        prepareCandidate(failure);
        UserCoupon persisted = exactUserCoupon();
        when(userCouponMapper.getByClaimId("claim-701")).thenReturn(null, persisted);
        when(seckillCouponMapper.getById(701L)).thenReturn(validCoupon());
        when(redisRepository.findClaimOwner(701L, "claim-701")).thenReturn("702");
        when(redisRepository.isUserClaimed(701L, 702L)).thenReturn(true);

        int processedCount = repairService.repairBatch();

        assertEquals(1, processedCount);
        verify(persistenceService).persist(any(SeckillCouponClaimMessage.class));
        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_RESOLVED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.FORWARD_REPAIR_SUCCESS.name()),
                anyString(),
                anyString());
    }

    /**
     * 修复前发现其他实例已经完成相同领取时应按幂等成功关闭。
     */
    @Test
    void shouldResolveWhenClaimWasAlreadyPersisted() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        prepareCandidate(failure);
        when(userCouponMapper.getByClaimId("claim-701")).thenReturn(exactUserCoupon());

        repairService.repairBatch();

        verify(persistenceService, never()).persist(any());
        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_RESOLVED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.ALREADY_PERSISTED.name()),
                anyString(),
                anyString());
    }

    /**
     * 原消息体无法解析时必须转人工，不能仅凭失败表字段发券。
     */
    @Test
    void shouldRejectRepairWhenOriginalMessageBodyIsInvalid() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        failure.setMessageBody("not-json");
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(701L)).thenReturn(validCoupon());

        repairService.repairBatch();

        verify(persistenceService, never()).persist(any());
        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.MESSAGE_BODY_INVALID.name()),
                anyString(),
                anyString());
    }

    /**
     * 白名单瞬时数据库错误未耗尽次数时应写入下一次退避时间。
     */
    @Test
    void shouldBackoffOnlyForTransientDatabaseFailure() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(701L)).thenReturn(validCoupon());
        when(redisRepository.findClaimOwner(701L, "claim-701")).thenReturn("702");
        when(redisRepository.isUserClaimed(701L, 702L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("temporary"))
                .when(persistenceService).persist(any());

        repairService.repairBatch();

        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_REPAIR_PENDING),
                any(LocalDateTime.class),
                eq(SeckillCouponClaimResolutionCode.REPAIR_TRANSIENT_FAILURE.name()),
                anyString(),
                anyString());
    }

    /**
     * 瞬时数据库故障耗尽修复预算后应转人工，不能无限制造重试流量。
     */
    @Test
    void shouldStopWhenTransientRepairBudgetIsExhausted() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        failure.setRepairAttempts(2);
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(701L)).thenReturn(validCoupon());
        when(redisRepository.findClaimOwner(701L, "claim-701")).thenReturn("702");
        when(redisRepository.isUserClaimed(701L, 702L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("temporary"))
                .when(persistenceService).persist(any());

        repairService.repairBatch();

        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.REPAIR_ATTEMPTS_EXHAUSTED.name()),
                anyString(),
                anyString());
    }

    /**
     * 原落库事务报告MySQL库存冲突时应转人工，流程6不得直接修改库存。
     */
    @Test
    void shouldNotAutomaticallyRepairMysqlStockConflict() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        prepareCandidate(failure);
        when(seckillCouponMapper.getById(701L)).thenReturn(validCoupon());
        when(redisRepository.findClaimOwner(701L, "claim-701")).thenReturn("702");
        when(redisRepository.isUserClaimed(701L, 702L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new SeckillCouponClaimPersistenceException(
                        SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT,
                        "stock conflict"))
                .when(persistenceService).persist(any());

        repairService.repairBatch();

        verify(failureMapper).completeRepair(
                eq(failure.getId()),
                eq(SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED),
                isNull(),
                eq(SeckillCouponClaimResolutionCode.REPAIR_BUSINESS_CONFLICT.name()),
                anyString(),
                anyString());
    }

    /**
     * CAS抢占失败时本实例不得读取证据或调用落库事务。
     */
    @Test
    void shouldSkipRepairWhenAnotherInstanceWinsCas() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        when(failureMapper.listRepairCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(failure));
        when(failureMapper.tryStartPendingRepair(
                eq(failure.getId()), eq(failure.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(0);

        int processedCount = repairService.repairBatch();

        assertEquals(0, processedCount);
        verify(persistenceService, never()).persist(any());
        verify(failureMapper, never()).completeRepair(
                any(), any(), any(), any(), any(), any());
    }

    /**
     * 应用在业务提交后宕机留下REPAIRING时，新实例应按租约接管并幂等关闭。
     */
    @Test
    void shouldReclaimExpiredRepairAndClosePersistedClaim() {
        SeckillCouponClaimFailure failure = repairPendingFailure();
        failure.setStatus(SeckillCouponClaimFailure.STATUS_REPAIRING);
        when(failureMapper.listRepairCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(failure));
        when(failureMapper.tryReclaimExpiredRepair(
                eq(failure.getId()),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString()))
                .thenReturn(1);
        when(failureMapper.completeRepair(
                eq(failure.getId()), anyString(), nullable(LocalDateTime.class),
                anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(userCouponMapper.getByClaimId("claim-701")).thenReturn(exactUserCoupon());

        int processedCount = repairService.repairBatch();

        assertEquals(1, processedCount);
        verify(failureMapper).tryReclaimExpiredRepair(
                eq(failure.getId()),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString());
        verify(persistenceService, never()).persist(any());
    }

    /**
     * 准备一个可成功抢占并提交结果的待修复记录。
     */
    private void prepareCandidate(SeckillCouponClaimFailure failure) {
        when(failureMapper.listRepairCandidates(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(failure));
        when(failureMapper.tryStartPendingRepair(
                eq(failure.getId()), eq(failure.getStatus()),
                any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(failureMapper.completeRepair(
                eq(failure.getId()), anyString(), nullable(LocalDateTime.class),
                anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    /**
     * 构造原消息体与失败业务身份完全一致的待修复记录。
     */
    private SeckillCouponClaimFailure repairPendingFailure() {
        return SeckillCouponClaimFailure.builder()
                .id(700L)
                .failureId("failure-700")
                .claimId("claim-701")
                .couponId(701L)
                .userId(702L)
                .status(SeckillCouponClaimFailure.STATUS_REPAIR_PENDING)
                .repairAttempts(0)
                .messageBody("{\"schemaVersion\":1,\"claimId\":\"claim-701\","
                        + "\"couponId\":701,\"userId\":702,\"claimedAt\":1000}")
                .build();
    }

    /**
     * 构造可供修复事务生成用户券有效期的秒杀券。
     */
    private SeckillCoupon validCoupon() {
        return SeckillCoupon.builder()
                .id(701L)
                .claimEndTime(LocalDateTime.now().plusHours(1))
                .build();
    }

    /**
     * 构造与治理记录完全一致的MySQL领取事实。
     */
    private UserCoupon exactUserCoupon() {
        return UserCoupon.builder()
                .claimId("claim-701")
                .couponId(701L)
                .userId(702L)
                .build();
    }
}
