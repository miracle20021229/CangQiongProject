package com.sky.seckill.mq.consumer;

import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import com.sky.seckill.service.SeckillCouponClaimFailureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 验证失败治理消费者的消息校验与事实落库边界。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimFailureConsumerTests {

    // 模拟最终失败事实持久化服务。
    @Mock
    private SeckillCouponClaimFailureService failureService;
    // 被测的失败治理消费者。
    private SeckillCouponClaimFailureConsumer consumer;

    /**
     * 为每个用例创建隔离的失败治理消费者。
     */
    @BeforeEach
    void setUp() {
        consumer = new SeckillCouponClaimFailureConsumer(failureService);
    }

    /**
     * 验证合法治理消息会委托领域服务幂等落库。
     */
    @Test
    void shouldPersistValidFailureGovernanceMessage() {
        SeckillCouponClaimFailureMessage message = validMessage();

        consumer.onMessage(message);

        verify(failureService).persist(message);
    }

    /**
     * 验证不受支持的治理消息版本在进入数据库前被拒绝。
     */
    @Test
    void shouldRejectUnsupportedGovernanceSchema() {
        SeckillCouponClaimFailureMessage message = validMessage();
        message.setSchemaVersion(2);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(message));

        verify(failureService, never()).persist(message);
    }

    /**
     * 创建满足消费者最小字段约束的失败治理消息。
     *
     * @return 合法失败治理消息
     */
    private SeckillCouponClaimFailureMessage validMessage() {
        return SeckillCouponClaimFailureMessage.builder()
                .schemaVersion(1)
                .failureId("failure-consumer")
                .action(SeckillCouponClaimFailureAction.QUARANTINE)
                .errorCode(SeckillCouponClaimFailureCode.INVALID_MESSAGE)
                .deliveryAttempts(1)
                .occurredAt(1000L)
                .build();
    }
}
