package com.sky.mq.producer;

import com.sky.mq.message.SeckillCouponCompensationMessage;
import com.sky.mq.message.SeckillCouponCompensationMessage.CompensationType;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponCompensationProducerTest {

    private static final String TOPIC = "test-seckill-coupon-compensation";

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private SendResult sendResult;

    private SeckillCouponCompensationProducer producer;

    @BeforeEach
    void setUp() {
        producer = new SeckillCouponCompensationProducer(rocketMQTemplate, TOPIC);
    }

    @Test
    void shouldSendActivitySnapshotMessageWithKeyAndLatestCouponId() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenReturn(sendResult);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(sendResult.getMsgId()).thenReturn("msg-1");

        assertTrue(producer.trySendActivitySnapshotSync(8L, "test"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<SeckillCouponCompensationMessage>> messageCaptor =
                ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(
                eq(TOPIC + ":" + CompensationType.ACTIVITY_SNAPSHOT_SYNC.name()),
                messageCaptor.capture());

        Message<SeckillCouponCompensationMessage> message = messageCaptor.getValue();
        SeckillCouponCompensationMessage payload = message.getPayload();
        assertEquals(8L, payload.getCouponId());
        assertEquals(1, payload.getSchemaVersion());
        assertEquals(CompensationType.ACTIVITY_SNAPSHOT_SYNC, payload.getType());
        assertNotNull(payload.getEventId());
        assertEquals(payload.getEventId(), message.getHeaders().get(RocketMQHeaders.KEYS));
    }

    @Test
    void shouldReturnFalseForNonSuccessSendResult() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenReturn(sendResult);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);

        assertFalse(producer.trySendAvailableCacheRebuild("test"));
    }

    /**
     * 业务参数错误应直接抛出，不能作为RocketMQ技术故障吞掉。
     */
    @Test
    void shouldRejectMissingCouponIdBeforeTryingToSend() {
        assertThrows(IllegalArgumentException.class,
                () -> producer.trySendActivitySnapshotSync(null, "test"));
    }
}
