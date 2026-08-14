package com.sky.service.impl;

import com.sky.dto.SeckillCouponDTO;
import com.sky.entity.SeckillCoupon;
import com.sky.event.SeckillCouponChangedEvent;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.service.support.SeckillCouponFinder;
import com.sky.service.support.SeckillCouponValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponAdminServiceImplTest {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private SeckillCouponFinder seckillCouponFinder;
    @Mock
    private SeckillCouponValidator seckillCouponValidator;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private SeckillCouponAdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        adminService = new SeckillCouponAdminServiceImpl(
                seckillCouponMapper,
                seckillCouponFinder,
                seckillCouponValidator,
                applicationEventPublisher);
    }

    @Test
    void shouldPublishCreatedEventAfterInsert() {
        SeckillCouponDTO dto = new SeckillCouponDTO();
        dto.setName(" 新人券 ");
        dto.setTotalStock(100);
        doAnswer(invocation -> {
            SeckillCoupon coupon = invocation.getArgument(0);
            coupon.setId(21L);
            return null;
        }).when(seckillCouponMapper).insert(any(SeckillCoupon.class));

        adminService.save(dto);

        ArgumentCaptor<SeckillCouponChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(SeckillCouponChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(21L, eventCaptor.getValue().couponId());
        assertEquals(SeckillCouponChangedEvent.ChangeType.CREATED,
                eventCaptor.getValue().changeType());
    }

    @Test
    void shouldPublishStatusEventWithoutDatabaseUpdateWhenStatusAlreadyMatches() {
        SeckillCoupon coupon = SeckillCoupon.builder()
                .id(22L)
                .status(0)
                .build();
        when(seckillCouponFinder.getByIdOrThrow(22L)).thenReturn(coupon);

        adminService.startOrStop(0, 22L);

        verify(seckillCouponMapper, never()).update(any(SeckillCoupon.class));
        verify(applicationEventPublisher).publishEvent(
                new SeckillCouponChangedEvent(
                        22L, SeckillCouponChangedEvent.ChangeType.ACTIVITY_REPAIR_REQUESTED));
    }
}
