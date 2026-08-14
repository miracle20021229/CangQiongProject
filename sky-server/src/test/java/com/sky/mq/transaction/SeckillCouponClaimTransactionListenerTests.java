package com.sky.mq.transaction;

import com.sky.constant.SeckillCouponClaimConstant;
import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.redis.SeckillCouponRedisRepository;
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

@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimTransactionListenerTests {

    @Mock
    private SeckillCouponRedisRepository redisRepository;
    private SeckillCouponClaimTransactionListener listener;
    private SeckillCouponClaimMessage claimMessage;

    @BeforeEach
    void setUp() {
        listener = new SeckillCouponClaimTransactionListener(redisRepository);
        claimMessage = SeckillCouponClaimMessage.builder().claimId("claim-2").couponId(21L).userId(22L).build();
    }

    @Test
    void shouldCommitAfterLuaPreDeductionSucceeds() {
        when(redisRepository.tryPreDeduct(21L, 22L, "claim-2")).thenReturn(SeckillCouponClaimConstant.SUCCESS);
        SeckillCouponClaimTransactionContext context = new SeckillCouponClaimTransactionContext(claimMessage);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(MessageBuilder.withPayload(claimMessage).build(), context);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        assertEquals(SeckillCouponClaimConstant.SUCCESS, context.getPreDeductResult());
    }

    @Test
    void shouldRollbackWhenLuaRejectsClaim() {
        when(redisRepository.tryPreDeduct(21L, 22L, "claim-2")).thenReturn(SeckillCouponClaimConstant.DUPLICATE_CLAIM);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(MessageBuilder.withPayload(claimMessage).build(), new SeckillCouponClaimTransactionContext(claimMessage));

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state);
    }

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
