package com.sky.mq.consumer;

import com.sky.mq.message.SeckillCouponCompensationMessage;
import com.sky.mq.message.SeckillCouponCompensationMessage.CompensationType;
import com.sky.service.SeckillCouponCacheSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponCompensationConsumerTest {

    @Mock
    private SeckillCouponCacheSyncService cacheSyncService;

    private SeckillCouponCompensationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SeckillCouponCompensationConsumer(cacheSyncService);
    }

    @Test
    void shouldRouteActivitySnapshotMessageByCouponId() {
        SeckillCouponCompensationMessage message = message(
                CompensationType.ACTIVITY_SNAPSHOT_SYNC, 9L);

        consumer.onMessage(message);

        verify(cacheSyncService).synchronizeCouponActivity(9L);
    }

    @Test
    void shouldRethrowBusinessFailureForRocketMqRetry() {
        SeckillCouponCompensationMessage message = message(
                CompensationType.AVAILABLE_CACHE_REBUILD, null);
        when(cacheSyncService.rebuildAvailableCouponCache())
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message));
    }

    @Test
    void shouldRejectUnsupportedSchemaBeforeBusinessHandling() {
        SeckillCouponCompensationMessage message = message(
                CompensationType.AVAILABLE_CACHE_REBUILD, null);
        message.setSchemaVersion(2);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(message));
        verifyNoInteractions(cacheSyncService);
    }

    private SeckillCouponCompensationMessage message(
            CompensationType type, Long couponId) {
        return SeckillCouponCompensationMessage.builder()
                .eventId("event-1")
                .schemaVersion(1)
                .type(type)
                .couponId(couponId)
                .reason("test")
                .occurredAt(System.currentTimeMillis())
                .build();
    }
}
