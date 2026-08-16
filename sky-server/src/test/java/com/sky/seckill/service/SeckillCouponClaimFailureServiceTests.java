package com.sky.seckill.service;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 验证流程5最终失败事实的消息校验、状态映射和幂等落库参数。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimFailureServiceTests {

    // 模拟失败治理记录数据访问接口。
    @Mock
    private SeckillCouponClaimFailureMapper failureMapper;
    // 被测的最终失败事实持久化服务。
    private SeckillCouponClaimFailureService failureService;

    /**
     * 为每个用例创建失败事实持久化服务。
     */
    @BeforeEach
    void setUp() {
        failureService = new SeckillCouponClaimFailureService(failureMapper);
    }

    /**
     * 验证待对账失败消息被完整映射为RECONCILE_PENDING治理记录。
     */
    @Test
    void shouldPersistOnlyFinalReconciliationFailure() {
        SeckillCouponClaimFailureMessage message = validFailureMessage();

        failureService.persist(message);

        ArgumentCaptor<SeckillCouponClaimFailure> captor = ArgumentCaptor.forClass(SeckillCouponClaimFailure.class);
        verify(failureMapper).upsertFinalFailure(captor.capture());
        SeckillCouponClaimFailure failure = captor.getValue();
        assertEquals("failure-5", failure.getFailureId());
        assertEquals("message-5", failure.getSourceMessageId());
        assertEquals("claim-5", failure.getClaimId());
        assertEquals(SeckillCouponClaimFailureAction.RECONCILE.name(), failure.getAction());
        assertEquals(SeckillCouponClaimFailure.STATUS_RECONCILE_PENDING, failure.getStatus());
        assertEquals(SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT.name(), failure.getErrorCode());
        assertEquals(2, failure.getDeliveryAttempts());
    }

    /**
     * 验证Broker重试事件不能绕过职责边界写入最终失败表。
     */
    @Test
    void shouldRejectRetryEventBecauseRetriesBelongToRocketMq() {
        SeckillCouponClaimFailureMessage message = validFailureMessage();
        message.setAction(SeckillCouponClaimFailureAction.RETRY);
        message.setErrorCode(SeckillCouponClaimFailureCode.MYSQL_TRANSIENT_FAILURE);

        assertThrows(IllegalArgumentException.class, () -> failureService.persist(message));

        verify(failureMapper, never()).upsertFinalFailure(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 创建满足流程5落库约束的失败治理消息。
     *
     * @return 合法失败治理消息
     */
    private SeckillCouponClaimFailureMessage validFailureMessage() {
        return SeckillCouponClaimFailureMessage.builder()
                .schemaVersion(1)
                .failureId("failure-5")
                .sourceMessageId("message-5")
                .sourceTopic("sky-seckill-coupon-claim")
                .claimId("claim-5")
                .couponId(51L)
                .userId(52L)
                .action(SeckillCouponClaimFailureAction.RECONCILE)
                .errorCode(SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT)
                .errorMessage("stock conflict")
                .messageBody("raw-body")
                .deliveryAttempts(2)
                .occurredAt(1000L)
                .build();
    }
}
