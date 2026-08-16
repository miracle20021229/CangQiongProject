package com.sky.seckill.mq.consumer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.enums.SeckillCouponClaimFailureAction;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.policy.SeckillCouponClaimRetryPolicy;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.producer.SeckillCouponClaimFailureProducer;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec.DecodeResult;
import com.sky.seckill.service.SeckillCouponClaimPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 流程4-步骤13～14：异步接收Broker已提交的领取消息，并调用独立MySQL事务持久化。
 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${sky.rocketmq.seckill-coupon-claim.topic:sky-seckill-coupon-claim}",
        consumerGroup = "${sky.rocketmq.seckill-coupon-claim.consumer-group:sky-seckill-coupon-claim-consumer}",
        selectorExpression = SeckillCouponClaimConstant.CLAIM_TAG,
        consumeThreadNumber = 4,
        consumeThreadMax = 16,
        maxReconsumeTimes = SeckillCouponClaimConstant.MAX_RECONSUME_TIMES
)
public class SeckillCouponClaimConsumer implements RocketMQListener<MessageExt> {

    // 复用流程4独立事务完成MySQL库存扣减和用户券落库。
    private final SeckillCouponClaimPersistenceService persistenceService;
    // 将不可直接重试的失败同步转入独立治理Topic。
    private final SeckillCouponClaimFailureProducer failureProducer;
    // 对数据库异常和唯一键竞争执行白名单分类。
    private final SeckillCouponClaimRetryPolicy retryPolicy;
    // 统一解析主Topic、重试Topic和死信队列中的领取消息。
    private final SeckillCouponClaimMessageCodec messageCodec;

    /**
     * 注入领取落库、失败治理发送、重试分类和原消息解析组件。
     *
     * @param persistenceService 领取落库事务服务
     * @param failureProducer    失败治理消息生产者
     * @param retryPolicy       选择性重试策略
     * @param messageCodec      原领取消息解析组件
     */
    public SeckillCouponClaimConsumer(SeckillCouponClaimPersistenceService persistenceService,
                                      SeckillCouponClaimFailureProducer failureProducer,
                                      SeckillCouponClaimRetryPolicy retryPolicy,
                                      SeckillCouponClaimMessageCodec messageCodec) {
        this.persistenceService = persistenceService;
        this.failureProducer = failureProducer;
        this.retryPolicy = retryPolicy;
        this.messageCodec = messageCodec;
    }

    /**
     * 流程4-步骤13：由RocketMQ消费线程异步调用，原HTTP请求不会等待本方法完成。
     * 方法完成代表主消息可以ACK；抛出白名单异常代表交给Broker延迟重试。
     *
     * @param sourceMessage RocketMQ原始领取消息
     */
    @Override
    public void onMessage(MessageExt sourceMessage) {
        DecodeResult decodeResult = messageCodec.decode(sourceMessage);
        SeckillCouponClaimMessage message = decodeResult.getMessage();
        if (!decodeResult.isDecoded()) {
            routeFailure(sourceMessage, decodeResult, SeckillCouponClaimFailureCode.INVALID_MESSAGE,
                    decodeResult.getDecodeException());
            return;
        }
        if (!message.hasRequiredFields()) {
            routeFailure(sourceMessage, decodeResult, SeckillCouponClaimFailureCode.INVALID_MESSAGE,
                    new IllegalArgumentException("秒杀券领取消息数据不完整"));
            return;
        }
        if (!Integer.valueOf(SeckillCouponClaimConstant.SUPPORTED_SCHEMA_VERSION).equals(message.getSchemaVersion())) {
            routeFailure(sourceMessage, decodeResult, SeckillCouponClaimFailureCode.UNSUPPORTED_SCHEMA_VERSION,
                    new IllegalArgumentException("秒杀券领取消息版本不受支持"));
            return;
        }

        log.info("开始持久化秒杀券领取消息，messageId={}，claimId={}，couponId={}，userId={}",
                sourceMessage.getMsgId(), message.getClaimId(), message.getCouponId(), message.getUserId());
        try {
            // 流程4-步骤14：调用独立持久化Service，事务边界从persist()进入。
            persistenceService.persist(message);
            log.info("秒杀券领取消息持久化完成，claimId={}", message.getClaimId());
        } catch (SeckillCouponClaimPersistenceException exception) {
            handleClassifiedFailure(sourceMessage, decodeResult, exception.getFailureCode(), exception);
        } catch (DuplicateKeyException exception) {
            SeckillCouponClaimFailureCode failureCode = retryPolicy.classifyDuplicateKey(sourceMessage.getReconsumeTimes());
            handleClassifiedFailure(sourceMessage, decodeResult, failureCode, exception);
        } catch (DataAccessException exception) {
            SeckillCouponClaimFailureCode failureCode = retryPolicy.classify(exception);
            handleClassifiedFailure(sourceMessage, decodeResult, failureCode, exception);
        } catch (RuntimeException exception) {
            routeFailure(sourceMessage, decodeResult, SeckillCouponClaimFailureCode.UNEXPECTED_FAILURE, exception);
        }
    }

    /**
     * 根据稳定失败码决定抛出异常交给Broker延迟重试，或同步转入失败治理Topic。
     *
     * @param sourceMessage RocketMQ原始领取消息
     * @param decodeResult  消息正文及解析结果
     * @param failureCode   已分类的稳定失败码
     * @param exception     当前消费异常
     */
    private void handleClassifiedFailure(MessageExt sourceMessage, DecodeResult decodeResult,
                                         SeckillCouponClaimFailureCode failureCode, RuntimeException exception) {
        if (failureCode.getAction() == SeckillCouponClaimFailureAction.RETRY) {
            log.warn("秒杀券领取将由 RocketMQ 延迟重试，messageId={}，claimId={}，reconsumeTimes={}，errorCode={}，exception={}，message={}",
                    sourceMessage.getMsgId(), decodeResult.getMessage().getClaimId(),
                    sourceMessage.getReconsumeTimes(), failureCode,
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw exception;
        }
        routeFailure(sourceMessage, decodeResult, failureCode, exception);
    }

    /**
     * 同步发送治理消息；发送成功后允许主消息ACK，发送失败则继续抛出以防失败事实丢失。
     *
     * @param sourceMessage RocketMQ原始领取消息
     * @param decodeResult  消息正文及解析结果
     * @param failureCode   决定治理初始状态的稳定失败码
     * @param exception     写入治理摘要的原始异常
     */
    private void routeFailure(MessageExt sourceMessage, DecodeResult decodeResult,
                              SeckillCouponClaimFailureCode failureCode, RuntimeException exception) {
        int deliveryAttempts = Math.max(sourceMessage.getReconsumeTimes() + 1, 1);
        // 治理消息同步发送成功后才 ACK 原消息；发送失败则抛出，避免失败事实丢失。
        failureProducer.publish(sourceMessage, decodeResult.getMessage(), decodeResult.getRawBody(),
                failureCode, exception, deliveryAttempts);
        if (failureCode == SeckillCouponClaimFailureCode.UNEXPECTED_FAILURE) {
            log.error("[SECKILL_CLAIM_GOVERNANCE_ALERT] 未知领取异常已转入治理 Topic，messageId={}，claimId={}，action={}，errorCode={}",
                    sourceMessage.getMsgId(), decodeResult.getMessage().getClaimId(),
                    failureCode.getAction(), failureCode, exception);
            return;
        }
        log.error("[SECKILL_CLAIM_GOVERNANCE_ALERT] 领取消息已转入治理 Topic，messageId={}，claimId={}，action={}，errorCode={}，exception={}，message={}",
                sourceMessage.getMsgId(), decodeResult.getMessage().getClaimId(),
                failureCode.getAction(), failureCode,
                exception.getClass().getSimpleName(), exception.getMessage());
    }
}
