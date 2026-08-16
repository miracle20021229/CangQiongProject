package com.sky.seckill.mq.producer;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.transaction.SeckillCouponClaimTransactionContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 流程3秒杀券事务消息生产者。
 */
@Component
@Slf4j
public class SeckillCouponClaimProducer {

    // 发送RocketMQ事务消息的客户端模板。
    private final RocketMQTemplate rocketMQTemplate;
    // 秒杀券领取主Topic名称。
    private final String topic;

    /**
     * 创建秒杀券领取事务消息生产者。
     *
     * @param rocketMQTemplate RocketMQ消息模板
     * @param topic 秒杀券领取主Topic
     */
    public SeckillCouponClaimProducer(
            RocketMQTemplate rocketMQTemplate,
            @Value("${sky.rocketmq.seckill-coupon-claim.topic:sky-seckill-coupon-claim}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    /**
     * 流程3-步骤4～6、12：创建本地Context并发送半消息，等待Listener执行Lua后读取回写结果。
     */
    public Long sendInTransaction(SeckillCouponClaimMessage payload) {
        // 发送前校验：Producer和Consumer共同复用消息结构完整性规则。
        if (payload == null || !payload.hasRequiredFields()) {
            throw new IllegalArgumentException("秒杀券领取消息数据不完整");
        }

        // 流程3-步骤4：创建仅在Producer本地使用的Context；构造器写入消息，Lua结果暂时为null。
        SeckillCouponClaimTransactionContext transactionContext = new SeckillCouponClaimTransactionContext(payload);

        // 为步骤5发送的事务消息构造消息体和Header；Header还会供Broker异常回查使用。
        Message<SeckillCouponClaimMessage> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, payload.getClaimId())
                .setHeader(SeckillCouponClaimMessage.CLAIM_ID_HEADER, payload.getClaimId())
                .setHeader(SeckillCouponClaimMessage.COUPON_ID_HEADER, payload.getCouponId())
                .setHeader(SeckillCouponClaimMessage.USER_ID_HEADER, payload.getUserId())
                .build();

        // 流程3-步骤5/6：调用事务发送API，RocketMQ先向Broker发送半消息，再同步回调步骤7执行Lua。
        TransactionSendResult sendResult = rocketMQTemplate.sendMessageInTransaction
                (topic + ":" + SeckillCouponClaimConstant.CLAIM_TAG, message, transactionContext);
        if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("秒杀券领取事务消息发送失败，claimId=" + payload.getClaimId());
        }
        if (LocalTransactionState.UNKNOW.equals(sendResult.getLocalTransactionState())) {
            throw new IllegalStateException("秒杀券领取事务状态待确认，claimId=" + payload.getClaimId());
        }

        // 流程3-步骤12：sendMessageInTransaction返回后，从同一个Context读取步骤11写入的Lua结果。
        Long preDeductResult = transactionContext.getPreDeductResult();
        if (preDeductResult == null) {
            throw new IllegalStateException("秒杀券领取本地事务未返回Lua结果，claimId=" + payload.getClaimId());
        }
        if (LocalTransactionState.COMMIT_MESSAGE.equals(sendResult.getLocalTransactionState()) && !SeckillCouponClaimConstant.SUCCESS.equals(preDeductResult)) {
            throw new IllegalStateException("秒杀券领取事务状态与Lua结果不一致，claimId=" + payload.getClaimId());
        }
        if (LocalTransactionState.ROLLBACK_MESSAGE.equals(sendResult.getLocalTransactionState()) && SeckillCouponClaimConstant.SUCCESS.equals(preDeductResult)) {
            throw new IllegalStateException("秒杀券领取事务状态与Lua结果不一致，claimId=" + payload.getClaimId());
        }
        if (!LocalTransactionState.COMMIT_MESSAGE.equals(sendResult.getLocalTransactionState()) && !LocalTransactionState.ROLLBACK_MESSAGE.equals(sendResult.getLocalTransactionState())) {
            throw new IllegalStateException("秒杀券领取事务返回未知状态，claimId=" + payload.getClaimId());
        }

        log.info("秒杀券领取事务消息处理完成，claimId={}，msgId={}，preDeductResult={}", payload.getClaimId(), sendResult.getMsgId(), preDeductResult);
        return preDeductResult;
    }
}
