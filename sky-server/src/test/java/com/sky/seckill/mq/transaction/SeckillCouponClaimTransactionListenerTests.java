package com.sky.seckill.mq.transaction;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 验证流程3本地事务监听器的Lua判定和Broker回查行为。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimTransactionListenerTests {

    // 模拟保存秒杀领取预扣证据的Redis仓储。
    @Mock
    private SeckillCouponRedisRepository redisRepository;
    // 被测试的RocketMQ本地事务监听器。
    private SeckillCouponClaimTransactionListener listener;
    // 每个用例复用的领取消息。
    private SeckillCouponClaimMessage claimMessage;

    /**
     * 初始化事务监听器和默认领取消息。
     */
    @BeforeEach
    void setUp() {
        listener = new SeckillCouponClaimTransactionListener(redisRepository);
        claimMessage = SeckillCouponClaimMessage.builder().claimId("claim-2").couponId(21L).userId(22L).build();
    }

    /**
     * 验证Lua预扣成功时提交事务并回写结果。
     */
    @Test
    void shouldCommitAfterLuaPreDeductionSucceeds() {
        when(redisRepository.tryPreDeduct(21L, 22L, "claim-2")).thenReturn(SeckillCouponClaimConstant.SUCCESS);
        SeckillCouponClaimTransactionContext context = new SeckillCouponClaimTransactionContext(claimMessage);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(MessageBuilder.withPayload(claimMessage).build(), context);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        assertEquals(SeckillCouponClaimConstant.SUCCESS, context.getPreDeductResult());
    }

    /**
     * 验证Lua业务拒绝时回滚事务消息。
     */
    @Test
    void shouldRollbackWhenLuaRejectsClaim() {
        when(redisRepository.tryPreDeduct(21L, 22L, "claim-2")).thenReturn(SeckillCouponClaimConstant.DUPLICATE_CLAIM);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(MessageBuilder.withPayload(claimMessage).build(), new SeckillCouponClaimTransactionContext(claimMessage));

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state);
    }

    /**
     * 验证Broker回查发现Redis领取标记时提交半消息。
     */
    @Test
    void shouldCommitBrokerCheckWhenRedisContainsClaimMarker() {
        when(redisRepository.isClaimPreDeducted(21L, 22L, "claim-2")).thenReturn(true);
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(SeckillCouponClaimMessage.CLAIM_ID_HEADER, "claim-2")
                .setHeader(SeckillCouponClaimMessage.COUPON_ID_HEADER, "21")
                .setHeader(SeckillCouponClaimMessage.USER_ID_HEADER, "22")
                .build();

        assertEquals(RocketMQLocalTransactionState.COMMIT, listener.checkLocalTransaction(message));
    }
}
