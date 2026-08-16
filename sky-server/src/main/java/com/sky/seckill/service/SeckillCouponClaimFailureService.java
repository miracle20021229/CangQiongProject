package com.sky.seckill.service;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mq.message.SeckillCouponClaimFailureMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 流程5：将治理 Topic 中的最终失败事实幂等落库。
 */
@Service
public class SeckillCouponClaimFailureService {

    // 将消息时间戳转换为业务时间时使用的统一时区。
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");

    // 负责失败事实幂等写入和后续治理查询的数据访问接口。
    private final SeckillCouponClaimFailureMapper failureMapper;

    /**
     * 注入失败治理记录数据访问接口。
     *
     * @param failureMapper 失败治理记录Mapper
     */
    public SeckillCouponClaimFailureService(SeckillCouponClaimFailureMapper failureMapper) {
        this.failureMapper = failureMapper;
    }

    /**
     * 校验治理消息并将最终失败事实幂等写入MySQL。
     */
    @Transactional
    public void persist(SeckillCouponClaimFailureMessage message) {
        if (message == null || !message.hasRequiredFields()) {
            throw new IllegalArgumentException("不能持久化不合法的领取失败治理消息");
        }

        LocalDateTime occurredTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(message.getOccurredAt()),
                BUSINESS_ZONE_ID
        );
        SeckillCouponClaimFailure failure = SeckillCouponClaimFailure.builder()
                .failureId(message.getFailureId())
                .sourceMessageId(message.getSourceMessageId())
                .sourceTopic(message.getSourceTopic())
                .claimId(message.getClaimId())
                .couponId(message.getCouponId())
                .userId(message.getUserId())
                .action(message.getAction().name())
                .status(message.getAction().getRecordStatus())
                .errorCode(message.getErrorCode().name())
                .errorMessage(message.getErrorMessage())
                .messageBody(message.getMessageBody())
                .deliveryAttempts(message.getDeliveryAttempts())
                .occurredTime(occurredTime)
                .build();
        failureMapper.upsertFinalFailure(failure);
    }
}
