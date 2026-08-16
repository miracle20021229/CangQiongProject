package com.sky.seckill.mq.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 统一解析主 Topic、重试 Topic 和 DLQ 中的领取消息。
 */
@Component
public class SeckillCouponClaimMessageCodec {

    /**
     * 解析RocketMQ原消息，并使用消息头补齐治理索引字段。
     */
    public DecodeResult decode(MessageExt messageExt) {
        if (messageExt == null) {
            throw new IllegalArgumentException("RocketMQ 领取消息为空");
        }

        byte[] body = messageExt.getBody();
        String rawBody = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        DecodeResult decodeResult = decodeRawBody(rawBody);
        supplementIndexFields(messageExt, decodeResult.getMessage());
        return decodeResult;
    }

    /**
     * 仅解析持久化保存的原始消息体，流程6B据此验证消息是否可安全修复。
     */
    public DecodeResult decodeRawBody(String rawBody) {
        String normalizedBody = rawBody == null ? "" : rawBody;
        SeckillCouponClaimMessage message = null;
        RuntimeException decodeException = null;

        if (normalizedBody.isBlank()) {
            decodeException = new IllegalArgumentException("秒杀券领取消息体为空");
        } else {
            try {
                message = JSON.parseObject(normalizedBody, SeckillCouponClaimMessage.class);
                if (message == null) {
                    decodeException = new IllegalArgumentException("秒杀券领取消息体解析结果为空");
                }
            } catch (JSONException exception) {
                decodeException = exception;
            }
        }

        if (message == null) {
            message = new SeckillCouponClaimMessage();
        }
        return new DecodeResult(message, normalizedBody, decodeException);
    }

    /**
     * 在消息体（body）里缺字段时，从 RocketMQ 消息头/keys 里把 claimId、couponId、userId
     * 三个"索引字段"兜底补回来。
     */
    private void supplementIndexFields(MessageExt messageExt, SeckillCouponClaimMessage message) {
        if (message.getClaimId() == null || message.getClaimId().isBlank()) {
            String claimId = messageExt.getUserProperty(SeckillCouponClaimMessage.CLAIM_ID_HEADER);
            message.setClaimId(claimId == null || claimId.isBlank() ? messageExt.getKeys() : claimId);
        }
        if (message.getCouponId() == null) {
            message.setCouponId(parseLong(messageExt.getUserProperty(SeckillCouponClaimMessage.COUPON_ID_HEADER)));
        }
        if (message.getUserId() == null) {
            message.setUserId(parseLong(messageExt.getUserProperty(SeckillCouponClaimMessage.USER_ID_HEADER)));
        }
    }
    /**
     * 将消息头中的数字字段安全转换为Long，非法值由后续结构校验处理。
     */
    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
    /**
     * 消息解析结果，同时保留原始正文和解析异常供流程5、6分类。
     */
    public static final class DecodeResult {

        // 解析后的领取消息；解析失败时保留空对象供索引字段补齐。
        private final SeckillCouponClaimMessage message;
        // 未修改的UTF-8消息正文。
        private final String rawBody;
        // 解析阶段捕获的异常；成功时为null。
        private final RuntimeException decodeException;

        /**
         * 创建一次消息解析结果。
         */
        public DecodeResult(SeckillCouponClaimMessage message, String rawBody, RuntimeException decodeException) {
            this.message = message;
            this.rawBody = rawBody;
            this.decodeException = decodeException;
        }

        /**
         * 返回解析后的领取消息。
         */
        public SeckillCouponClaimMessage getMessage() {
            return message;
        }

        /**
         * 返回未修改的原始消息正文。
         */
        public String getRawBody() {
            return rawBody;
        }

        /**
         * 返回解析异常，成功时为null。
         */
        public RuntimeException getDecodeException() {
            return decodeException;
        }

        /**
         * 判断正文是否已成功解析。
         */
        public boolean isDecoded() {
            return decodeException == null;
        }
    }
}
