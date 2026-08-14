package com.sky.mq.consumer;

import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.service.impl.SeckillCouponClaimPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimConsumerTests {

    @Mock
    private SeckillCouponClaimPersistenceService persistenceService;
    private SeckillCouponClaimConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SeckillCouponClaimConsumer(persistenceService);
    }

    @Test
    void shouldPersistValidClaimMessage() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-3")
                .couponId(31L)
                .userId(32L)
                .claimedAt(1000L)
                .build();

        consumer.onMessage(message);

        verify(persistenceService).persist(message);
    }

    @Test
    void shouldRejectUnsupportedMessage() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder().schemaVersion(2).claimId("claim-3").couponId(31L).userId(32L).claimedAt(1000L).build();

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(message));
        verify(persistenceService, never()).persist(message);
    }

    @Test
    void shouldRejectIncompleteMessage() {
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder().schemaVersion(1).claimId("claim-3").userId(32L).claimedAt(1000L).build();

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(message));
        verify(persistenceService, never()).persist(message);
    }
}
