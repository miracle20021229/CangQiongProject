package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_KEY;
import static com.sky.constant.SeckillCouponCacheConstant.AVAILABLE_LIST_TTL_SECONDS;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCouponUserQueryServiceImplTest {

    @Mock
    private SeckillCouponMapper seckillCouponMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CacheClient cacheClient;

    private SeckillCouponUserQueryServiceImpl queryService;

    @BeforeEach
    void setUp() {
        queryService = new SeckillCouponUserQueryServiceImpl(
                seckillCouponMapper,
                userCouponMapper,
                cacheClient);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    /**
     * 验证可领取列表查询由用户查询服务负责，并把MySQL查询作为缓存回源逻辑。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryAvailableCouponsThroughLogicalExpireCache() {
        List<SeckillCouponVO> coupons = Collections.singletonList(new SeckillCouponVO());
        when(seckillCouponMapper.listAvailable(any())).thenReturn(coupons);
        when(cacheClient.queryWithLogicalExpire(
                eq(AVAILABLE_LIST_KEY),
                any(Type.class),
                any(Supplier.class),
                eq(AVAILABLE_LIST_TTL_SECONDS),
                eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> ((Supplier<List<SeckillCouponVO>>) invocation.getArgument(2)).get());

        List<SeckillCouponVO> result = queryService.listAvailable();

        assertSame(coupons, result);
        verify(seckillCouponMapper).listAvailable(any());
    }

    /**
     * 验证个人秒杀券查询仍使用当前登录用户ID。
     */
    @Test
    void shouldQueryCurrentUsersCoupons() {
        BaseContext.setCurrentId(41L);
        List<UserCouponVO> coupons = Collections.singletonList(new UserCouponVO());
        when(userCouponMapper.listByUserId(41L)).thenReturn(coupons);

        List<UserCouponVO> result = queryService.listMine();

        assertSame(coupons, result);
        verify(userCouponMapper).listByUserId(41L);
    }
}
