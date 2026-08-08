package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.support.SeckillCouponFinder;
import com.sky.service.support.SeckillCouponValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponUserClaimServiceImplTest {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private SeckillCouponFinder seckillCouponFinder;
    @Mock
    private SeckillCouponValidator seckillCouponValidator;

    private SeckillCouponUserClaimServiceImpl claimService;

    @BeforeEach
    void setUp() {
        claimService = new SeckillCouponUserClaimServiceImpl(
                seckillCouponMapper,
                userCouponMapper,
                seckillCouponFinder,
                seckillCouponValidator);
        BaseContext.setCurrentId(41L);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldKeepMysqlTransactionClaimBaselineAfterExtraction() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(42L)
                .claimEndTime(LocalDateTime.now().plusHours(1))
                .build();
        when(seckillCouponFinder.getByIdOrThrow(42L)).thenReturn(coupon);
        when(userCouponMapper.countByCouponIdAndUserId(42L, 41L)).thenReturn(0);
        when(seckillCouponMapper.decreaseStock(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        doAnswer(invocation -> {
            UserCoupon userCoupon = invocation.getArgument(0);
            userCoupon.setId(43L);
            return null;
        }).when(userCouponMapper).insert(any(UserCoupon.class));

        Long userCouponId = claimService.claim(42L);

        assertEquals(43L, userCouponId);
        verify(seckillCouponValidator).validateClaimable(
                org.mockito.ArgumentMatchers.eq(coupon),
                org.mockito.ArgumentMatchers.any());
        verify(seckillCouponMapper).decreaseStock(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any());
    }
}
