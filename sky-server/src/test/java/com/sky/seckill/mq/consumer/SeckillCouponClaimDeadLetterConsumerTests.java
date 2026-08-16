package com.sky.seckill.mq.consumer;

import com.alibaba.fastjson2.JSON;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.producer.SeckillCouponClaimFailureProducer;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 验证主消费组死信消息只转入治理Topic，不直接修改MySQL业务数据。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimDeadLetterConsumerTests {

    // 模拟失败治理消息生产者。
    @Mock
    private SeckillCouponClaimFailureProducer failureProducer;
    // 被测的主消费组死信消费者。
    private SeckillCouponClaimDeadLetterConsumer consumer;

    /**
     * 为每个用例创建包含真实消息解析器的死信消费者。
     */
    @BeforeEach
    void setUp() {
        consumer = new SeckillCouponClaimDeadLetterConsumer(
                failureProducer,
                new SeckillCouponClaimMessageCodec()
        );
    }

    /**
     * 验证合法死信按RETRY_EXHAUSTED分类同步转入失败治理Topic。
     */
    @Test
    void shouldForwardDeadLetterToFailureTopicInsteadOfWritingMysqlDirectly() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-dlq")
                .couponId(61L)
                .userId(62L)
                .claimedAt(1000L)
                .build();
        MessageExt messageExt = messageExt("message-dlq", JSON.toJSONString(message));
        messageExt.setReconsumeTimes(5);

        consumer.onMessage(messageExt);

        verify(failureProducer).publish(
                eq(messageExt),
                eq(message),
                eq(JSON.toJSONString(message)),
                eq(SeckillCouponClaimFailureCode.RETRY_EXHAUSTED),
                any(RuntimeException.class),
                eq(6)
        );
    }

    /**
     * 验证死信正文损坏时仍从消息头保留对账所需业务索引。
     */
    @Test
    void shouldKeepHeaderIndexesWhenDeadLetterBodyIsMalformed() {
        MessageExt messageExt = messageExt("message-malformed", "not-json");
        messageExt.putUserProperty(SeckillCouponClaimMessage.CLAIM_ID_HEADER, "claim-from-header");
        messageExt.putUserProperty(SeckillCouponClaimMessage.COUPON_ID_HEADER, "71");
        messageExt.putUserProperty(SeckillCouponClaimMessage.USER_ID_HEADER, "72");

        consumer.onMessage(messageExt);

        ArgumentCaptor<SeckillCouponClaimMessage> captor = ArgumentCaptor.forClass(SeckillCouponClaimMessage.class);
        verify(failureProducer).publish(
                eq(messageExt),
                captor.capture(),
                eq("not-json"),
                eq(SeckillCouponClaimFailureCode.RETRY_EXHAUSTED),
                any(RuntimeException.class),
                eq(6)
        );
        assertEquals("claim-from-header", captor.getValue().getClaimId());
        assertEquals(71L, captor.getValue().getCouponId());
        assertEquals(72L, captor.getValue().getUserId());
    }

    /**
     * 创建携带指定消息ID和UTF-8正文的RocketMQ死信消息。
     *
     * @param messageId RocketMQ消息ID
     * @param body      消息正文
     * @return RocketMQ死信消息
     */
    private MessageExt messageExt(String messageId, String body) {
        MessageExt messageExt = new MessageExt();
        messageExt.setMsgId(messageId);
        messageExt.setTopic("%DLQ%sky-seckill-coupon-claim-consumer");
        messageExt.setBody(body.getBytes(StandardCharsets.UTF_8));
        return messageExt;
    }
}
