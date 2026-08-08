package com.sky.mq;

import com.sky.mq.consumer.SeckillCouponCompensationConsumer;
import com.sky.mq.producer.SeckillCouponCompensationProducer;
import com.sky.service.SeckillCouponCacheSyncService;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实RocketMQ收发测试。
 * 默认跳过，只有本机NameServer和Broker已启动时才显式执行。
 */
@EnabledIfEnvironmentVariable(named = "RUN_ROCKETMQ_IT", matches = "true")
@SpringBootTest(
        classes = SeckillCouponCompensationRocketMqIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rocketmq.name-server=127.0.0.1:9876",
                "rocketmq.producer.group=sky-seckill-compensation-integration-test-producer",
                "sky.rocketmq.seckill-coupon-compensation.topic=sky-seckill-coupon-compensation",
                "sky.rocketmq.seckill-coupon-compensation.consumer-group=sky-seckill-compensation-integration-test-consumer"
        }
)
class SeckillCouponCompensationRocketMqIntegrationTest {

    @Autowired
    private SeckillCouponCompensationProducer producer;
    @Autowired
    private RecordingCacheSyncService cacheSyncService;

    @Test
    void shouldSendAndConsumeActivitySnapshotCompensationMessage() throws InterruptedException {
        assertTrue(producer.trySendActivitySnapshotSync(18L, "integration-test"));

        assertTrue(cacheSyncService.await(10, TimeUnit.SECONDS));
        assertEquals(18L, cacheSyncService.getCouponId());
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            RocketMQAutoConfiguration.class,
            SeckillCouponCompensationProducer.class,
            SeckillCouponCompensationConsumer.class
    })
    static class TestApplication {

        @Bean
        RecordingCacheSyncService cacheSyncService() {
            return new RecordingCacheSyncService();
        }
    }

    static class RecordingCacheSyncService
            implements SeckillCouponCacheSyncService {

        private final CountDownLatch consumed = new CountDownLatch(1);
        private volatile Long couponId;

        @Override
        public void warmUpAvailableCouponCache() {
        }

        @Override
        public int rebuildAvailableCouponCache() {
            consumed.countDown();
            return 0;
        }

        @Override
        public void synchronizeCouponActivity(Long couponId) {
            this.couponId = couponId;
            consumed.countDown();
        }

        boolean await(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return consumed.await(timeout, timeUnit);
        }

        Long getCouponId() {
            return couponId;
        }
    }
}
