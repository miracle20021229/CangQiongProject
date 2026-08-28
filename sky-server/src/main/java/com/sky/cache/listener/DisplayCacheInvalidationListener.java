package com.sky.cache.listener;

import com.sky.cache.DisplayCacheService;
import com.sky.cache.event.DisplayCacheInvalidationEvent;
import com.sky.cache.mq.DisplayCacheInvalidationProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务边界：MySQL成功提交后才删除L1/L2并广播其他实例。
 */
@Component
public class DisplayCacheInvalidationListener {

    private final DisplayCacheService displayCacheService;
    private final DisplayCacheInvalidationProducer producer;

    public DisplayCacheInvalidationListener(DisplayCacheService displayCacheService, DisplayCacheInvalidationProducer producer) {
        this.displayCacheService = displayCacheService;
        this.producer = producer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(DisplayCacheInvalidationEvent event) {
        //清除失效keys
        displayCacheService.evict(event.keys());
        //发送清除失效keys消息
        producer.send(event.keys());
    }
}
