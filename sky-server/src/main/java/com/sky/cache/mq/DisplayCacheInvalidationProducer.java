package com.sky.cache.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 数据库提交后异步广播L1失效；发送失败由短L1 TTL最终修复，不回滚业务事务。
 */
@Component
@Slf4j
public class DisplayCacheInvalidationProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public DisplayCacheInvalidationProducer(RocketMQTemplate rocketMQTemplate, @Value("${sky.cache.invalidation.topic:sky-cache-invalidation}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    public void send(Collection<String> keys) {
        DisplayCacheInvalidationMessage payload = new DisplayCacheInvalidationMessage(new ArrayList<>(keys));
        try {
            rocketMQTemplate.asyncSend(
                    topic,
                    MessageBuilder.withPayload(payload).build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.debug("展示缓存失效广播成功，keys={}，msgId={}", keys, sendResult.getMsgId());
                        }

                        @Override
                        public void onException(Throwable exception) {
                            log.error("展示缓存失效广播失败，等待L1 TTL兜底，keys={}", keys, exception);
                        }
                    });
        } catch (RuntimeException exception) {
            log.error("展示缓存失效广播提交失败，等待L1 TTL兜底，keys={}", keys, exception);
        }
    }
}
