package com.sky.seckill.mq.support;

import com.alibaba.fastjson2.JSON;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证主Topic、重试Topic和死信队列共用的领取消息解析规则。
 */
class SeckillCouponClaimMessageCodecTests {

    // 被测的领取消息编解码组件。
    private final SeckillCouponClaimMessageCodec messageCodec = new SeckillCouponClaimMessageCodec();

    /**
     * 验证合法JSON正文能够完整还原领取消息。
     */
    @Test
    void shouldDecodeValidClaimBody() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-codec")
                .couponId(81L)
                .userId(82L)
                .claimedAt(1000L)
                .build();
        MessageExt messageExt = messageExt(JSON.toJSONString(message));

        SeckillCouponClaimMessageCodec.DecodeResult result = messageCodec.decode(messageExt);

        assertTrue(result.isDecoded());
        assertEquals(message, result.getMessage());
    }

    /**
     * 验证正文损坏时仍保留原文，并从消息头补齐治理索引字段。
     */
    @Test
    void shouldPreserveRawBodyAndSupplementIndexesAfterDecodeFailure() {
        MessageExt messageExt = messageExt("not-json");
        messageExt.putUserProperty(SeckillCouponClaimMessage.CLAIM_ID_HEADER, "claim-header");
        messageExt.putUserProperty(SeckillCouponClaimMessage.COUPON_ID_HEADER, "91");
        messageExt.putUserProperty(SeckillCouponClaimMessage.USER_ID_HEADER, "92");

        SeckillCouponClaimMessageCodec.DecodeResult result = messageCodec.decode(messageExt);

        assertFalse(result.isDecoded());
        assertEquals("not-json", result.getRawBody());
        assertEquals("claim-header", result.getMessage().getClaimId());
        assertEquals(91L, result.getMessage().getCouponId());
        assertEquals(92L, result.getMessage().getUserId());
    }

    /**
     * 创建携带指定UTF-8正文的RocketMQ测试消息。
     *
     * @param body 消息正文
     * @return RocketMQ原始消息
     */
    private MessageExt messageExt(String body) {
        MessageExt messageExt = new MessageExt();
        messageExt.setBody(body.getBytes(StandardCharsets.UTF_8));
        return messageExt;
    }
}
