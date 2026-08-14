package com.sky.service.impl;

import com.sky.constant.SeckillCouponClaimConstant;
import com.sky.context.BaseContext;
import com.sky.exception.CouponBusinessException;
import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.mq.producer.SeckillCouponClaimProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponUserClaimServiceImplTest {

    @Mock
    private SeckillCouponClaimProducer claimProducer;

    private SeckillCouponUserClaimServiceImpl claimService;

    @BeforeEach
    void setUp() {
        claimService = new SeckillCouponUserClaimServiceImpl(claimProducer);
        BaseContext.setCurrentId(41L);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldReturnClaimIdAfterTransactionMessageCommits() {
        when(claimProducer.sendInTransaction(any(SeckillCouponClaimMessage.class))).thenReturn(SeckillCouponClaimConstant.SUCCESS);

        String claimId = claimService.claim(42L);

        assertFalse(claimId.isBlank());
        ArgumentCaptor<SeckillCouponClaimMessage> messageCaptor = ArgumentCaptor.forClass(SeckillCouponClaimMessage.class);
        verify(claimProducer).sendInTransaction(messageCaptor.capture());
        assertEquals(claimId, messageCaptor.getValue().getClaimId());
        assertEquals(42L, messageCaptor.getValue().getCouponId());
        assertEquals(41L, messageCaptor.getValue().getUserId());
    }

    @Test
    void shouldTranslateDuplicateLuaResult() {
        when(claimProducer.sendInTransaction(any(SeckillCouponClaimMessage.class))).thenReturn(SeckillCouponClaimConstant.DUPLICATE_CLAIM);

        CouponBusinessException exception = assertThrows(CouponBusinessException.class, () -> claimService.claim(42L));

        assertEquals("每位用户限领一张，请勿重复领取", exception.getMessage());
    }

    @Test
    void shouldHideProducerFailureBehindBusinessException() {
        when(claimProducer.sendInTransaction(any(SeckillCouponClaimMessage.class))).thenThrow(new IllegalStateException("broker unavailable"));

        CouponBusinessException exception = assertThrows(CouponBusinessException.class, () -> claimService.claim(42L));

        assertEquals("领取请求提交失败，请稍后查询券包或重试", exception.getMessage());
    }
}
