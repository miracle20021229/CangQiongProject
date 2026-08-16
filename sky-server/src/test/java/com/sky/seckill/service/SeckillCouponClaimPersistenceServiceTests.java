package com.sky.seckill.service;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
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

/**
 * 验证流程4领取落库事务的幂等、一人一券、库存条件更新和失败分类。
 */
@ExtendWith(MockitoExtension.class)
class SeckillCouponClaimPersistenceServiceTests {

    // 模拟秒杀券活动和库存数据访问接口。
    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    // 模拟用户券幂等事实和写入数据访问接口。
    @Mock
    private UserCouponMapper userCouponMapper;
    // 被测的流程4领取落库事务服务。
    private SeckillCouponClaimPersistenceService persistenceService;
    // 每个用例复用的合法领取消息。
    private SeckillCouponClaimMessage message;

    /**
     * 为每个用例创建落库服务和固定业务身份的合法领取消息。
     */
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

    /**
     * 验证MySQL条件扣库存成功后插入携带claimId的用户券记录。
     */
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

    /**
     * 验证完全一致的claimId重复消息按幂等成功跳过写入。
     */
    @Test
    void shouldSkipAnAlreadyPersistedClaimMessage() {
        when(userCouponMapper.getByClaimId("claim-4")).thenReturn(UserCoupon.builder().claimId("claim-4").couponId(41L).userId(42L).build());

        persistenceService.persist(message);

        verify(seckillCouponMapper, never()).decreaseStockAfterPreDeduct(41L);
        verify(userCouponMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserCoupon.class));
    }

    /**
     * 验证MySQL库存条件扣减失败时抛出稳定库存冲突异常以触发事务回滚。
     */
    @Test
    void shouldRollbackWhenMysqlStockCannotBeDecreased() {
        when(seckillCouponMapper.getById(41L)).thenReturn(SeckillCoupon.builder().id(41L).claimEndTime(LocalDateTime.now().plusHours(1)).build());
        when(seckillCouponMapper.decreaseStockAfterPreDeduct(41L)).thenReturn(0);

        SeckillCouponClaimPersistenceException exception = assertThrows(
                SeckillCouponClaimPersistenceException.class,
                () -> persistenceService.persist(message)
        );

        assertEquals(SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT, exception.getFailureCode());
        verify(userCouponMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserCoupon.class));
    }

    /**
     * 验证同一claimId绑定不同业务身份时分类为永久冲突。
     */
    @Test
    void shouldClassifyClaimIdConflictAsPermanent() {
        when(userCouponMapper.getByClaimId("claim-4")).thenReturn(UserCoupon.builder()
                .claimId("claim-4")
                .couponId(99L)
                .userId(42L)
                .build());

        SeckillCouponClaimPersistenceException exception = assertThrows(
                SeckillCouponClaimPersistenceException.class,
                () -> persistenceService.persist(message)
        );

        assertEquals(SeckillCouponClaimFailureCode.CLAIM_ID_CONFLICT, exception.getFailureCode());
        verify(seckillCouponMapper, never()).decreaseStockAfterPreDeduct(41L);
    }

    /**
     * 验证用户已通过另一claimId领取时分类为一人一券冲突。
     */
    @Test
    void shouldClassifyAlreadyClaimedUserAsPermanent() {
        when(userCouponMapper.getByCouponIdAndUserId(41L, 42L)).thenReturn(UserCoupon.builder()
                .claimId("another-claim")
                .couponId(41L)
                .userId(42L)
                .build());

        SeckillCouponClaimPersistenceException exception = assertThrows(
                SeckillCouponClaimPersistenceException.class,
                () -> persistenceService.persist(message)
        );

        assertEquals(SeckillCouponClaimFailureCode.USER_ALREADY_CLAIMED, exception.getFailureCode());
        verify(seckillCouponMapper, never()).decreaseStockAfterPreDeduct(41L);
    }
}
