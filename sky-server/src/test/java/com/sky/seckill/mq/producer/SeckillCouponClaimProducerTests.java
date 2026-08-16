package com.sky.seckill.mq.producer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.transaction.SeckillCouponClaimTransactionContext;
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

/**
 * 验证流程3事务消息生产者的发送结果、上下文回写和前置校验。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimProducerTests {

    // 模拟RocketMQ事务消息发送模板。
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    // 被测试的秒杀券领取消息生产者。
    private SeckillCouponClaimProducer producer;
    // 每个用例复用的合法领取消息。
    private SeckillCouponClaimMessage payload;

    /**
     * 初始化生产者和默认合法消息。
     */
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

    /**
     * 验证事务提交后返回Lua成功结果并写入关键消息头。
     */
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

    /**
     * 验证业务型Lua拒绝随事务回滚结果返回给调用方。
     */
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

    /**
     * 验证Broker返回未知事务状态时拒绝向上伪装成功。
     */
    @Test
    void shouldRejectUnknownTransactionState() {
        TransactionSendResult sendResult = sendResult(LocalTransactionState.UNKNOW);
        when(rocketMQTemplate.sendMessageInTransaction(any(String.class), any(Message.class), any(SeckillCouponClaimTransactionContext.class))).thenReturn(sendResult);

        assertThrows(IllegalStateException.class, () -> producer.sendInTransaction(payload));
    }

    /**
     * 验证不完整消息在访问RocketMQ前被拦截。
     */
    @Test
    void shouldRejectIncompleteMessageBeforeSending() {
        payload.setClaimId(" ");

        assertThrows(IllegalArgumentException.class, () -> producer.sendInTransaction(payload));
        verifyNoInteractions(rocketMQTemplate);
    }

    /**
     * 构造指定本地事务状态的成功发送结果。
     *
     * @param state RocketMQ本地事务状态
     * @return 可供生产者测试使用的事务发送结果
     */
    private TransactionSendResult sendResult(LocalTransactionState state) {
        TransactionSendResult result = new TransactionSendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        result.setLocalTransactionState(state);
        result.setMsgId("msg-1");
        return result;
    }
}
