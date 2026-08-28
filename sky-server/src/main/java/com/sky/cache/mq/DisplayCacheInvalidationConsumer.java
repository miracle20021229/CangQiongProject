package com.sky.cache.mq;

import com.sky.cache.DisplayCacheService;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 广播消费保证每个应用实例都清理自己的Caffeine L1。
 */
@Component
@RocketMQMessageListener(
        topic = "${sky.cache.invalidation.topic:sky-cache-invalidation}",
        consumerGroup = "${sky.cache.invalidation.consumer-group:sky-cache-invalidation-consumer}",
        messageModel = MessageModel.BROADCASTING)
public class DisplayCacheInvalidationConsumer implements RocketMQListener<DisplayCacheInvalidationMessage> {

    private final DisplayCacheService displayCacheService;
    public DisplayCacheInvalidationConsumer(DisplayCacheService displayCacheService) {
        this.displayCacheService = displayCacheService;
    }

    @Override
    public void onMessage(DisplayCacheInvalidationMessage message) {
        if (message != null) {
            // 每个实例都清L1，并再次尝试删除L2，补偿写实例短暂的锁竞争或Redis异常。
            displayCacheService.evict(message.getKeys());
        }
    }
}
