package com.sky.seckill.governance.support;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.enums.SeckillCouponClaimResolutionCode;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec.DecodeResult;
import org.springframework.stereotype.Component;

/**
 * 流程6B原始领取消息的解析、版本和业务身份检查器。
 */
@Component
public class ClaimMessageInspector {

    private final SeckillCouponClaimMessageCodec messageCodec;

    public ClaimMessageInspector(SeckillCouponClaimMessageCodec messageCodec) {
        this.messageCodec = messageCodec;
    }

    /**
     * 解析失败记录中的原始消息，并返回可落库消息或稳定治理结论。
     */
    public MessageEvidence inspect(SeckillCouponClaimFailure failure) {
        DecodeResult decodeResult = messageCodec.decodeRawBody(failure.getMessageBody());
        if (!decodeResult.isDecoded()) {
            return new MessageEvidence(
                    null,
                    SeckillCouponClaimResolutionCode.MESSAGE_BODY_INVALID,
                    ResolutionFormatter.summarize(decodeResult.getDecodeException())
            );
        }

        SeckillCouponClaimMessage message = decodeResult.getMessage();
        if (!message.hasRequiredFields()
                || !Integer.valueOf(SeckillCouponClaimConstant.SUPPORTED_SCHEMA_VERSION)
                .equals(message.getSchemaVersion())) {
            return new MessageEvidence(
                    null,
                    SeckillCouponClaimResolutionCode.MESSAGE_BODY_INVALID,
                    "原领取消息字段不完整或版本不受支持"
            );
        }
        if (!failure.getClaimId().equals(message.getClaimId())
                || !failure.getCouponId().equals(message.getCouponId())
                || !failure.getUserId().equals(message.getUserId())) {
            return new MessageEvidence(
                    null,
                    SeckillCouponClaimResolutionCode.MESSAGE_IDENTITY_CONFLICT,
                    "原消息体与失败记录的claimId、couponId或userId不一致"
            );
        }
        return new MessageEvidence(message, null, null);
    }

    /**
     * 流程6B原始消息检查结果；code为空表示message可以安全进入后续修复。
     */
    public record MessageEvidence(SeckillCouponClaimMessage message,
                                  SeckillCouponClaimResolutionCode code,
                                  String detail) {
    }
}
