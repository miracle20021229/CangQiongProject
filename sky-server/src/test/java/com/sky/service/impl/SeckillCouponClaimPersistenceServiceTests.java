package com.sky.service.impl;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.mq.message.SeckillCouponClaimMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimPersistenceServiceTests {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    private SeckillCouponClaimPersistenceService persistenceService;
    private SeckillCouponClaimMessage message;

    @BeforeEach
    void setUp() {
        persistenceService = new SeckillCouponClaimPersistenceService(seckillCouponMapper, userCouponMapper);
        message = SeckillCouponClaimMessage.builder()
                .schemaVersion(1)
                .claimId("claim-4")
                .couponId(41L)
                .userId(42L)
                .claimedAt(1000L)
                .build();
    }

    @Test
    void shouldDecreaseMysqlStockAndInsertUserCoupon() {
        LocalDateTime claimEndTime = LocalDateTime.now().plusHours(1);
        when(seckillCouponMapper.getById(41L)).thenReturn(SeckillCoupon.builder().id(41L).claimEndTime(claimEndTime).build());
        when(seckillCouponMapper.decreaseStockAfterPreDeduct(41L)).thenReturn(1);

        persistenceService.persist(message);

        verify(seckillCouponMapper).decreaseStockAfterPreDeduct(41L);
        ArgumentCaptor<UserCoupon> couponCaptor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(couponCaptor.capture());
        assertEquals("claim-4", couponCaptor.getValue().getClaimId());
        assertEquals(42L, couponCaptor.getValue().getUserId());
        assertEquals(claimEndTime, couponCaptor.getValue().getExpireTime());
    }

    @Test
    void shouldSkipAnAlreadyPersistedClaimMessage() {
        when(userCouponMapper.getByClaimId("claim-4")).thenReturn(UserCoupon.builder().claimId("claim-4").couponId(41L).userId(42L).build());

        persistenceService.persist(message);

        verify(seckillCouponMapper, never()).decreaseStockAfterPreDeduct(41L);
        verify(userCouponMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserCoupon.class));
    }

    @Test
    void shouldRollbackWhenMysqlStockCannotBeDecreased() {
        when(seckillCouponMapper.getById(41L)).thenReturn(SeckillCoupon.builder().id(41L).claimEndTime(LocalDateTime.now().plusHours(1)).build());
        when(seckillCouponMapper.decreaseStockAfterPreDeduct(41L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> persistenceService.persist(message));
        verify(userCouponMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserCoupon.class));
    }
}
