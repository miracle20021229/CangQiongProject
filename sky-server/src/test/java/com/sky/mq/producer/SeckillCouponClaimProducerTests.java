package com.sky.mq.producer;

import com.sky.constant.SeckillCouponClaimConstant;
import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.mq.transaction.SeckillCouponClaimTransactionContext;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimProducerTests {

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    private SeckillCouponClaimProducer producer;
    private SeckillCouponClaimMessage payload;

    @BeforeEach
    void setUp() {
        producer = new SeckillCouponClaimProducer(rocketMQTemplate, "claim-topic");
        payload = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-1")
                .couponId(11L)
                .userId(12L)
                .claimedAt(1000L)
                .build();
    }

    @Test
    void shouldReturnLuaResultAfterTransactionCommit() {
        TransactionSendResult sendResult = sendResult(LocalTransactionState.COMMIT_MESSAGE);
        when(rocketMQTemplate.sendMessageInTransaction(eq("claim-topic:" + SeckillCouponClaimConstant.CLAIM_TAG), any(Message.class), any(SeckillCouponClaimTransactionContext.class))).thenAnswer(invocation -> {
            SeckillCouponClaimTransactionContext context = invocation.getArgument(2);
            context.setPreDeductResult(SeckillCouponClaimConstant.SUCCESS);
            return sendResult;
        });

        assertEquals(SeckillCouponClaimConstant.SUCCESS, producer.sendInTransaction(payload));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(eq("claim-topic:" + SeckillCouponClaimConstant.CLAIM_TAG), messageCaptor.capture(), any(SeckillCouponClaimTransactionContext.class));
        assertEquals("claim-1", messageCaptor.getValue().getHeaders().get(SeckillCouponClaimMessage.CLAIM_ID_HEADER));
        assertEquals(11L, messageCaptor.getValue().getHeaders().get(SeckillCouponClaimMessage.COUPON_ID_HEADER));
    }

    @Test
    void shouldReturnBusinessLuaResultAfterTransactionRollback() {
        TransactionSendResult sendResult = sendResult(LocalTransactionState.ROLLBACK_MESSAGE);
        when(rocketMQTemplate.sendMessageInTransaction(any(String.class), any(Message.class), any(SeckillCouponClaimTransactionContext.class))).thenAnswer(invocation -> {
            SeckillCouponClaimTransactionContext context = invocation.getArgument(2);
            context.setPreDeductResult(SeckillCouponClaimConstant.OUT_OF_STOCK);
            return sendResult;
        });

        assertEquals(SeckillCouponClaimConstant.OUT_OF_STOCK, producer.sendInTransaction(payload));
    }

    @Test
    void shouldRejectUnknownTransactionState() {
        TransactionSendResult sendResult = sendResult(LocalTransactionState.UNKNOW);
        when(rocketMQTemplate.sendMessageInTransaction(any(String.class), any(Message.class), any(SeckillCouponClaimTransactionContext.class))).thenReturn(sendResult);

        assertThrows(IllegalStateException.class, () -> producer.sendInTransaction(payload));
    }

    @Test
    void shouldRejectIncompleteMessageBeforeSending() {
        payload.setClaimId(" ");

        assertThrows(IllegalArgumentException.class, () -> producer.sendInTransaction(payload));
        verifyNoInteractions(rocketMQTemplate);
    }

    private TransactionSendResult sendResult(LocalTransactionState state) {
        TransactionSendResult result = new TransactionSendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        result.setLocalTransactionState(state);
        result.setMsgId("msg-1");
        return result;
    }
}
