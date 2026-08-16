package com.sky.seckill.mq.consumer;

import com.alibaba.fastjson2.JSON;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.policy.SeckillCouponClaimRetryPolicy;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.producer.SeckillCouponClaimFailureProducer;
import com.sky.seckill.mq.support.SeckillCouponClaimMessageCodec;
import com.sky.seckill.service.SeckillCouponClaimPersistenceService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证主领取消费者对成功、重试、隔离、对账和治理发送失败的完整路由规则。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimConsumerTests {

    // 模拟流程4领取落库事务服务。
    @Mock
    private SeckillCouponClaimPersistenceService persistenceService;
    // 模拟流程5失败治理消息生产者。
    @Mock
    private SeckillCouponClaimFailureProducer failureProducer;
    // 被测的主领取消息消费者。
    private SeckillCouponClaimConsumer consumer;

    /**
     * 为每个用例创建使用真实重试策略和消息解析器的消费者。
     */
    @BeforeEach
    void setUp() {
        consumer = new SeckillCouponClaimConsumer(
                persistenceService,
                failureProducer,
                new SeckillCouponClaimRetryPolicy(),
                new SeckillCouponClaimMessageCodec()
        );
    }

    /**
     * 验证合法领取消息只进入流程4落库，不产生失败治理消息。
     */
    @Test
    void shouldPersistValidClaimWithoutWritingFailureRecord() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-3", message);

        consumer.onMessage(sourceMessage);

        verify(persistenceService).persist(message);
        verifyNoInteractions(failureProducer);
    }

    /**
     * 验证无法解析的正文直接隔离，不触发Broker盲目重试。
     */
    @Test
    void shouldQuarantineMalformedBodyWithoutBrokerRetry() {
        MessageExt sourceMessage = rawMessageExt("message-malformed", "not-json");
        sourceMessage.putUserProperty(SeckillCouponClaimMessage.CLAIM_ID_HEADER, "claim-header");

        consumer.onMessage(sourceMessage);

        verify(failureProducer).publish(
                eq(sourceMessage),
                any(SeckillCouponClaimMessage.class),
                eq("not-json"),
                eq(SeckillCouponClaimFailureCode.INVALID_MESSAGE),
                any(RuntimeException.class),
                eq(1)
        );
        verifyNoPersistence();
    }

    /**
     * 验证必填字段不完整的消息直接隔离且不调用领取落库。
     */
    @Test
    void shouldQuarantineIncompleteMessageWithoutBrokerRetry() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-3")
                .userId(32L)
                .claimedAt(1000L)
                .build();
        MessageExt sourceMessage = messageExt("message-incomplete", message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.INVALID_MESSAGE, 1);
        verifyNoPersistence();
    }

    /**
     * 验证不受支持的消息版本直接隔离且不触发Broker重试。
     */
    @Test
    void shouldQuarantineUnsupportedSchemaWithoutBrokerRetry() {
        SeckillCouponClaimMessage message = validMessage();
        message.setSchemaVersion(2);
        MessageExt sourceMessage = messageExt("message-version", message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.UNSUPPORTED_SCHEMA_VERSION, 1);
        verifyNoPersistence();
    }

    /**
     * 验证MySQL库存冲突进入流程6对账而不是重复扣库存。
     */
    @Test
    void shouldRouteStockConflictToReconciliationWithoutRetry() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-stock", message);
        SeckillCouponClaimPersistenceException exception = new SeckillCouponClaimPersistenceException(
                SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT,
                "stock conflict"
        );
        doThrow(exception).when(persistenceService).persist(message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT, 1);
    }

    /**
     * 验证白名单中的瞬时数据库故障通过抛异常交给Broker延迟重投。
     */
    @Test
    void shouldRetryOnlyWhitelistedTransientDatabaseFailure() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-db-down", message);
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException("mysql unavailable");
        doThrow(exception).when(persistenceService).persist(message);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> consumer.onMessage(sourceMessage));

        assertSame(exception, thrown);
        verifyNoInteractions(failureProducer);
    }

    /**
     * 验证非瞬时数据库故障直接隔离，避免无效重试放大故障。
     */
    @Test
    void shouldQuarantineNonTransientDatabaseFailureWithoutRetry() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-data-invalid", message);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("invalid column value");
        doThrow(exception).when(persistenceService).persist(message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.MYSQL_NON_TRANSIENT_FAILURE, 1);
    }

    /**
     * 验证首次唯一键竞争被视为短暂并发，可由Broker重试一次。
     */
    @Test
    void shouldRetryDuplicateKeyRaceOnlyOnce() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-duplicate", message);
        DuplicateKeyException exception = new DuplicateKeyException("concurrent insert");
        doThrow(exception).when(persistenceService).persist(message);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> consumer.onMessage(sourceMessage));

        assertSame(exception, thrown);
        verifyNoInteractions(failureProducer);
    }

    /**
     * 验证重复出现的唯一键冲突转入流程6对账而不继续重试。
     */
    @Test
    void shouldRouteRepeatedDuplicateKeyToReconciliation() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-duplicate-retry", message);
        sourceMessage.setReconsumeTimes(1);
        DuplicateKeyException exception = new DuplicateKeyException("concurrent insert");
        doThrow(exception).when(persistenceService).persist(message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.MYSQL_DUPLICATE_CONFLICT, 2);
    }

    /**
     * 验证未知运行时异常被隔离并告警，不执行无限盲重试。
     */
    @Test
    void shouldQuarantineUnexpectedRuntimeFailureInsteadOfBlindRetry() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-runtime", message);
        IllegalStateException exception = new IllegalStateException("programming defect");
        doThrow(exception).when(persistenceService).persist(message);

        consumer.onMessage(sourceMessage);

        verifyFailureRouted(sourceMessage, message, SeckillCouponClaimFailureCode.UNEXPECTED_FAILURE, 1);
    }

    /**
     * 验证治理Topic发送失败时原消息不会ACK，而是继续由Broker重投。
     */
    @Test
    void shouldRetryOriginalMessageWhenFailureTopicPublishFails() {
        SeckillCouponClaimMessage message = validMessage();
        MessageExt sourceMessage = messageExt("message-route-failed", message);
        RuntimeException publishException = new IllegalStateException("failure topic unavailable");
        doThrow(publishException).when(failureProducer).publish(
                eq(sourceMessage),
                eq(message),
                any(String.class),
                eq(SeckillCouponClaimFailureCode.UNSUPPORTED_SCHEMA_VERSION),
                any(RuntimeException.class),
                eq(1)
        );
        message.setSchemaVersion(2);
        sourceMessage.setBody(JSON.toJSONBytes(message));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> consumer.onMessage(sourceMessage));

        assertSame(publishException, thrown);
    }

    /**
     * 校验消费者向失败生产者传递了完整来源、失败码和投递次数。
     *
     * @param sourceMessage    原始RocketMQ消息
     * @param message          解析后的领取消息
     * @param failureCode      预期稳定失败码
     * @param deliveryAttempts 预期累计投递次数
     */
    private void verifyFailureRouted(MessageExt sourceMessage,
                                     SeckillCouponClaimMessage message,
                                     SeckillCouponClaimFailureCode failureCode,
                                     int deliveryAttempts) {
        verify(failureProducer).publish(
                eq(sourceMessage),
                eq(message),
                eq(JSON.toJSONString(message)),
                eq(failureCode),
                any(RuntimeException.class),
                eq(deliveryAttempts)
        );
    }

    /**
     * 校验当前失败分支没有调用流程4领取落库服务。
     */
    private void verifyNoPersistence() {
        verify(persistenceService, never()).persist(any(SeckillCouponClaimMessage.class));
    }

    /**
     * 创建满足主消费者结构约束的领取消息。
     *
     * @return 合法领取消息
     */
    private SeckillCouponClaimMessage validMessage() {
        return SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-3")
                .couponId(31L)
                .userId(32L)
                .claimedAt(1000L)
                .build();
    }

    /**
     * 把领取对象序列化为RocketMQ原始测试消息。
     *
     * @param messageId RocketMQ消息ID
     * @param message   领取消息对象
     * @return RocketMQ原始消息
     */
    private MessageExt messageExt(String messageId, SeckillCouponClaimMessage message) {
        return rawMessageExt(messageId, JSON.toJSONString(message));
    }

    /**
     * 创建携带指定消息ID与原始UTF-8正文的RocketMQ测试消息。
     *
     * @param messageId RocketMQ消息ID
     * @param body      原始消息正文
     * @return RocketMQ原始消息
     */
    private MessageExt rawMessageExt(String messageId, String body) {
        MessageExt messageExt = new MessageExt();
        messageExt.setMsgId(messageId);
        messageExt.setTopic("sky-seckill-coupon-claim");
        messageExt.setBody(body.getBytes(StandardCharsets.UTF_8));
        return messageExt;
    }
}
