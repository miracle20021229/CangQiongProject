package com.sky.seckill.mq.producer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证失败治理消息的稳定幂等ID、发送确认和重试职责边界。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimFailureProducerTests {

    // 模拟RocketMQ同步发送模板。
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    // 模拟Broker发送确认结果。
    @Mock
    private SendResult sendResult;
    // 被测的失败治理消息生产者。
    private SeckillCouponClaimFailureProducer failureProducer;

    /**
     * 为每个用例创建绑定固定治理Topic的生产者。
     */
    @BeforeEach
    void setUp() {
        failureProducer = new SeckillCouponClaimFailureProducer(
                rocketMQTemplate,
                "sky-seckill-coupon-claim-failure"
        );
    }

    /**
     * 验证同一领取与同类失败跨Broker重投仍生成相同failureId。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldPublishIdempotentFailureEventBeforeOriginalAck() {
        when(rocketMQTemplate.syncSend(eq("sky-seckill-coupon-claim-failure:" + SeckillCouponClaimConstant.FAILURE_TAG),
                any(Message.class))).thenReturn(sendResult);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(sendResult.getMsgId()).thenReturn("failure-message-id");
        MessageExt sourceMessage = sourceMessage();
        SeckillCouponClaimMessage claimMessage = claimMessage();

        failureProducer.publish(sourceMessage, claimMessage, "raw-body",
                SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT,
                new IllegalStateException("stock conflict"), 2);
        // Broker 重新投递时 msgId 可能变化，但同一 claimId 的同类失败仍必须幂等。
        sourceMessage.setMsgId("message-source-retry");
        failureProducer.publish(sourceMessage, claimMessage, "raw-body",
                SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT,
                new IllegalStateException("stock conflict"), 2);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate, org.mockito.Mockito.times(2)).syncSend(
                eq("sky-seckill-coupon-claim-failure:" + SeckillCouponClaimConstant.FAILURE_TAG),
                captor.capture()
        );
        List<Message> sentMessages = captor.getAllValues();
        SeckillCouponClaimFailureMessage first = (SeckillCouponClaimFailureMessage) sentMessages.get(0).getPayload();
        SeckillCouponClaimFailureMessage second = (SeckillCouponClaimFailureMessage) sentMessages.get(1).getPayload();
        assertNotNull(first.getFailureId());
        assertEquals(first.getFailureId(), second.getFailureId());
        assertEquals(first.getFailureId(), sentMessages.get(0).getHeaders().get(RocketMQHeaders.KEYS));
        assertEquals(SeckillCouponClaimFailureAction.RECONCILE, first.getAction());
        assertEquals("message-source", first.getSourceMessageId());
        assertEquals(2, first.getDeliveryAttempts());
    }

    /**
     * 验证纯重试动作由Broker负责，不能写入失败治理Topic。
     */
    @Test
    void shouldRejectRetryActionBecauseBrokerOwnsRetryMessages() {
        assertThrows(IllegalArgumentException.class, () -> failureProducer.publish(
                sourceMessage(),
                claimMessage(),
                "raw-body",
                SeckillCouponClaimFailureCode.MYSQL_TRANSIENT_FAILURE,
                new IllegalStateException("temporary failure"),
                1
        ));

        verify(rocketMQTemplate, never()).syncSend(any(String.class), any(Message.class));
    }

    /**
     * 验证Broker未确认治理消息持久化时生产者抛错，阻止原消息ACK。
     */
    @Test
    void shouldFailOriginalConsumptionWhenGovernanceMessageIsNotStoredByBroker() {
        when(rocketMQTemplate.syncSend(eq("sky-seckill-coupon-claim-failure:" + SeckillCouponClaimConstant.FAILURE_TAG),
                any(Message.class))).thenReturn(sendResult);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);

        assertThrows(IllegalStateException.class, () -> failureProducer.publish(
                sourceMessage(),
                claimMessage(),
                "raw-body",
                SeckillCouponClaimFailureCode.INVALID_MESSAGE,
                new IllegalArgumentException("invalid message"),
                1
        ));
    }

    /**
     * 创建携带稳定来源身份的RocketMQ测试消息。
     *
     * @return 原始RocketMQ消息
     */
    private MessageExt sourceMessage() {
        MessageExt messageExt = new MessageExt();
        messageExt.setMsgId("message-source");
        messageExt.setTopic("sky-seckill-coupon-claim");
        messageExt.setKeys("claim-producer");
        return messageExt;
    }

    /**
     * 创建满足主领取消息结构约束的测试消息。
     *
     * @return 秒杀券领取消息
     */
    private SeckillCouponClaimMessage claimMessage() {
        return SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-producer")
                .couponId(101L)
                .userId(102L)
                .claimedAt(1000L)
                .build();
    }
}
