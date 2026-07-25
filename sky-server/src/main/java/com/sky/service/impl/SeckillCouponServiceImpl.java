package com.sky.service.impl;

import com.alibaba.fastjson2.TypeReference;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.redis.SeckillCouponRedisRepository;
import com.sky.result.PageResult;
import com.sky.service.SeckillCouponService;
import com.sky.utils.CacheClient;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀券业务实现。
 *
 * 流程1：可领取列表已经接入逻辑过期、启动预热、事务提交后刷新和冷缓存互斥初始化。
 * 流程2：秒杀活动状态、领取时间和库存已经在事务提交后同步到Redis，并支持启动恢复。
 * TODO 流程3：使用Lua原子完成秒杀资格判断、库存预扣和一人一券校验，并发送MQ。
 * TODO 流程4：增加MQ消费者，通过独立事务Service完成MySQL落库和消费幂等。
 * TODO 流程5：在订单业务中完成优惠券锁定、核销和取消释放。
 * TODO 流程6：增加消息重试、库存补偿以及Redis与MySQL定时对账。
 */
@Service
@Slf4j
public class SeckillCouponServiceImpl implements SeckillCouponService {

    private static final String AVAILABLE_COUPON_CACHE_KEY =
            "cache:seckill:coupon:available";
    private static final long AVAILABLE_COUPON_CACHE_TTL_SECONDS = 30L;
    private static final Type AVAILABLE_COUPON_LIST_TYPE =
            new TypeReference<List<SeckillCouponVO>>() {}.getType();

    @Autowired
    private SeckillCouponMapper seckillCouponMapper;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private SeckillCouponRedisRepository seckillCouponRedisRepository;

    /**
     * ----------------此处往下几个是CRUD传统业务----------
     */

    /**
     * 新增秒杀券。
     *
     * @param seckillCouponDTO 秒杀券信息
     */
    @Override
    @Transactional
    public void save(SeckillCouponDTO seckillCouponDTO) {
        validateCoupon(seckillCouponDTO, false);

        SeckillCoupon seckillCoupon = new SeckillCoupon();
        BeanUtils.copyProperties(seckillCouponDTO, seckillCoupon);
        seckillCoupon.setId(null);
        seckillCoupon.setName(seckillCouponDTO.getName().trim());
        seckillCoupon.setRemainingStock(seckillCouponDTO.getTotalStock());
        seckillCoupon.setStatus(StatusConstant.DISABLE);

        seckillCouponMapper.insert(seckillCoupon);

        // 数据库提交成功后重建可领取列表；事务回滚时不会修改Redis
        refreshAvailableCouponCacheAfterCommit();
    }

    /**
     * 秒杀券分页查询。
     *
     * @param queryDTO 分页查询条件
     * @return 秒杀券分页数据
     */
    @Override
    public PageResult pageQuery(SeckillCouponPageQueryDTO queryDTO) {
        if (queryDTO == null || queryDTO.getPage() <= 0 || queryDTO.getPageSize() <= 0) {
            throw new CouponBusinessException("页码和每页记录数必须大于0");
        }

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());
        Page<SeckillCouponVO> page = seckillCouponMapper.pageQuery(queryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 修改秒杀券。
     *
     * @param seckillCouponDTO 秒杀券信息
     */
    @Override
    @Transactional
    public void update(SeckillCouponDTO seckillCouponDTO) {
        validateCoupon(seckillCouponDTO, true);

        SeckillCoupon currentCoupon = getCouponOrThrow(seckillCouponDTO.getId());
        if (StatusConstant.ENABLE.equals(currentCoupon.getStatus())) {
            throw new CouponBusinessException("启用中的秒杀券不能修改，请先停用");
        }

        int claimedStock = currentCoupon.getTotalStock() - currentCoupon.getRemainingStock();
        if (seckillCouponDTO.getTotalStock() < claimedStock) {
            throw new CouponBusinessException("总库存不能小于已领取数量：" + claimedStock);
        }

        SeckillCoupon seckillCoupon = new SeckillCoupon();
        BeanUtils.copyProperties(seckillCouponDTO, seckillCoupon);
        seckillCoupon.setName(seckillCouponDTO.getName().trim());
        seckillCoupon.setRemainingStock(seckillCouponDTO.getTotalStock() - claimedStock);

        seckillCouponMapper.update(seckillCoupon);

        // 数据库提交成功后重建可领取列表，避免缓存与数据库状态不一致
        refreshAvailableCouponCacheAfterCommit();
    }

    /**
     * 启用或停用秒杀券。
     *
     * @param status 秒杀券状态
     * @param id     秒杀券ID
     */
    @Override
    @Transactional
    public void startOrStop(Integer status, Long id) {
        if (id == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new CouponBusinessException("秒杀券状态只能是0或1");
        }

        SeckillCoupon currentCoupon = getCouponOrThrow(id);
        if (status.equals(currentCoupon.getStatus())) {
            refreshAvailableCouponCacheAfterCommit();
            synchronizeCouponActivityAfterCommit(currentCoupon);
            return;
        }

        if (StatusConstant.ENABLE.equals(status)) {
            if (currentCoupon.getRemainingStock() == null || currentCoupon.getRemainingStock() <= 0) {
                throw new CouponBusinessException("剩余库存不足，不能启用秒杀券");
            }
            if (currentCoupon.getClaimEndTime() == null
                    || !currentCoupon.getClaimEndTime().isAfter(LocalDateTime.now())) {
                throw new CouponBusinessException("领取时间已结束，不能启用秒杀券");
            }
        }

        SeckillCoupon seckillCoupon = SeckillCoupon.builder()
                .id(id)
                .status(status)
                .build();
        seckillCouponMapper.update(seckillCoupon);

        currentCoupon.setStatus(status);
        // 数据库提交成功后同时刷新展示列表和Redis秒杀活动快照
        refreshAvailableCouponCacheAfterCommit();
        synchronizeCouponActivityAfterCommit(currentCoupon);
    }

    /**
     * ---------------此处是核心查询业务-----------------
     */

    /**
     * 用户端查询可领取的秒杀券。
     * 查询条件由 SQL 统一保证：已启用、当前处于领取时间内且剩余库存大于0。
     * @return 可领取的秒杀券列表
     */
    @Override
    public List<SeckillCouponVO> listAvailable() {
        // 1. 热点列表使用逻辑过期查询，过期请求立即返回旧数据并触发异步重建
        return cacheClient.queryWithLogicalExpire(AVAILABLE_COUPON_CACHE_KEY, AVAILABLE_COUPON_LIST_TYPE,
                () -> seckillCouponMapper.listAvailable(LocalDateTime.now()),
                AVAILABLE_COUPON_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Spring Boot启动完成后预热可领取列表，并恢复已启用且未结束的Redis秒杀活动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAvailableCouponCacheAfterStartup() {
        // 启动预热走加锁+双重检查，多实例只有一个查库，其余等待后读新缓存
        cacheClient.warmUpWithLogicalExpire(AVAILABLE_COUPON_CACHE_KEY, AVAILABLE_COUPON_LIST_TYPE,
                () -> seckillCouponMapper.listAvailable(LocalDateTime.now()),
                AVAILABLE_COUPON_CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        List<SeckillCoupon> enabledCoupons = seckillCouponMapper.listEnabledNotEnded(LocalDateTime.now());
        int restoredCount = 0;
        for (SeckillCoupon coupon : enabledCoupons) {
            try {
                seckillCouponRedisRepository.syncActivity(coupon);
                restoredCount++;
            } catch (RuntimeException exception) {
                log.error("Redis秒杀活动恢复失败，couponId={}", coupon.getId(), exception);
            }
        }
        log.info("秒杀券Redis预热完成，恢复活动数量={}", restoredCount);
    }

    /**
     * 数据库事务提交成功后刷新列表缓存，事务回滚时不修改Redis。
     */
    private void refreshAvailableCouponCacheAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            rebuildAvailableCouponCache("秒杀券事务提交");
                        }
                    }
            );
            return;
        }
        rebuildAvailableCouponCache("秒杀券数据变更");
    }

    /**
     * 数据库事务提交成功后同步Redis秒杀活动快照。
     * 启用和停用共用同一份快照，停用只修改状态，不删除已领取用户集合。
     */
    private void synchronizeCouponActivityAfterCommit(SeckillCoupon coupon) {
        Runnable synchronizeTask = () -> {
            try {
                seckillCouponRedisRepository.syncActivity(coupon);
                log.info("Redis秒杀活动同步完成，couponId={}，status={}", coupon.getId(), coupon.getStatus());
            } catch (RuntimeException exception) {
                log.error("Redis秒杀活动同步失败，couponId={}，status={}", coupon.getId(), coupon.getStatus(), exception);
                // TODO 流程6：通过可靠消息或定时对账补偿Redis同步失败
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronizeTask.run();
                }
            });
            return;
        }
        synchronizeTask.run();
    }

    /**
     * 查询数据库并重建可领取秒杀券列表缓存。
     */
    private void rebuildAvailableCouponCache(String reason) {
        try {
            List<SeckillCouponVO> coupons =
                    seckillCouponMapper.listAvailable(LocalDateTime.now());
            cacheClient.setWithLogicalExpire(AVAILABLE_COUPON_CACHE_KEY, coupons,
                    AVAILABLE_COUPON_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("可领取秒杀券缓存刷新完成，reason={}，数量={}", reason, coupons.size());
        } catch (RuntimeException exception) {
            log.error("可领取秒杀券缓存刷新失败，reason={}", reason, exception);
            // TODO 流程6：通过可靠消息或定时对账补偿数据库或Redis的短暂故障
        }
    }

    /**
     * -----------重点------------
     */

    /**
     * 当前登录用户领取秒杀券。
     * 这里先使用 MySQL 条件更新完成基础版本：先校验活动和一人一券，
     * 再原子扣减数据库库存并写入用户领券记录。整个过程在同一事务中，
     * 任意一步失败都会回滚。企业版接入Redis Lua和MQ后，请求线程只负责
     * 原子预扣与发送消息，数据库条件扣库存和新增用户券移动到消费者事务中。
     * @param couponId 秒杀券ID
     * @return 用户领券记录ID
     */
    @Override
    @Transactional
    public Long claim(Long couponId) {
        if (couponId == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }

        Long userId = getCurrentUserIdOrThrow();
        LocalDateTime now = LocalDateTime.now();
        SeckillCoupon coupon = getCouponOrThrow(couponId);

        // TODO 流程3：企业版请求入口应从Redis读取活动并执行Lua，避免秒杀请求先访问MySQL。
        // 先做可读性较好的业务校验，便于向用户返回明确的失败原因。
        validateClaimableCoupon(coupon, now);

        Integer claimedCount = userCouponMapper.countByCouponIdAndUserId(couponId, userId);
        if (claimedCount != null && claimedCount > 0) {
            throw new CouponBusinessException("每位用户限领一张，请勿重复领取");
        }

        // TODO 流程3：Lua成功后发送MQ并立即返回领取流水ID，不在请求线程同步修改MySQL。
        // TODO 流程4：将下面的条件扣库存和新增用户券移动到MQ消费者调用的独立事务Service中。

        /*
         * SQL 同时判断状态、时间和库存，并通过 remaining_stock > 0
         * 防止并发请求把数据库库存扣成负数。
         */
        int affectedRows = seckillCouponMapper.decreaseStock(couponId, now);
        if (affectedRows != 1) {
            throw new CouponBusinessException("秒杀券已抢完或活动状态已变化，请刷新后重试");
        }

        UserCoupon userCoupon = UserCoupon.builder()
                .couponId(couponId)
                .userId(userId)
                .status(UserCoupon.UNUSED)
                .claimTime(now)
                .expireTime(coupon.getClaimEndTime())
                .build();

        try {
            userCouponMapper.insert(userCoupon);
        } catch (DuplicateKeyException ex) {
            /*
             * 数据库唯一索引 uk_coupon_user 是并发场景下“一人一券”的最终兜底。
             * 抛出业务异常后，本事务中已经扣减的数据库库存也会自动回滚。
             */
            throw new CouponBusinessException("每位用户限领一张，请勿重复领取");
        }

        return userCoupon.getId();
    }

    /**
     * 用户端查询我的秒杀券。
     * Mapper 会在查询时把已经超过有效期但仍标记为未使用的券展示为已过期。
     * @return 当前用户的秒杀券列表
     */
    @Override
    public List<UserCouponVO> listMine() {
        Long userId = getCurrentUserIdOrThrow();
        return userCouponMapper.listByUserId(userId);
    }

    /**
     * 校验秒杀券当前是否允许领取。
     *
     * @param coupon 秒杀券活动
     * @param now    当前时间
     */
    private void validateClaimableCoupon(SeckillCoupon coupon, LocalDateTime now) {
        if (!StatusConstant.ENABLE.equals(coupon.getStatus())) {
            throw new CouponBusinessException("秒杀券未启用");
        }
        if (coupon.getClaimStartTime() == null || now.isBefore(coupon.getClaimStartTime())) {
            throw new CouponBusinessException("秒杀券领取活动尚未开始");
        }
        if (coupon.getClaimEndTime() == null || !now.isBefore(coupon.getClaimEndTime())) {
            throw new CouponBusinessException("秒杀券领取活动已结束");
        }
        if (coupon.getRemainingStock() == null || coupon.getRemainingStock() <= 0) {
            throw new CouponBusinessException("秒杀券已抢完");
        }
    }

    /**
     * 获取当前登录用户ID，未登录时抛出业务异常。
     *
     * @return 当前用户ID
     */
    private Long getCurrentUserIdOrThrow() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new CouponBusinessException("用户未登录");
        }
        return userId;
    }

    /**
     * 校验新增或修改秒杀券时提交的业务参数。
     *
     * @param seckillCouponDTO 秒杀券信息
     * @param requireId        是否要求必须传入秒杀券ID
     */
    private void validateCoupon(SeckillCouponDTO seckillCouponDTO, boolean requireId) {
        if (seckillCouponDTO == null) {
            throw new CouponBusinessException("秒杀券信息不能为空");
        }
        if (requireId && seckillCouponDTO.getId() == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }
        if (!StringUtils.hasText(seckillCouponDTO.getName())) {
            throw new CouponBusinessException("秒杀券名称不能为空");
        }
        if (seckillCouponDTO.getName().trim().length() > 64) {
            throw new CouponBusinessException("秒杀券名称长度不能超过64个字符");
        }
        if (seckillCouponDTO.getThresholdAmount() == null
                || seckillCouponDTO.getThresholdAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new CouponBusinessException("使用门槛金额不能小于0");
        }
        if (seckillCouponDTO.getDiscountAmount() == null
                || seckillCouponDTO.getDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CouponBusinessException("优惠金额必须大于0");
        }
        if (seckillCouponDTO.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0
                && seckillCouponDTO.getDiscountAmount()
                .compareTo(seckillCouponDTO.getThresholdAmount()) > 0) {
            throw new CouponBusinessException("优惠金额不能大于使用门槛金额");
        }
        if (seckillCouponDTO.getTotalStock() == null || seckillCouponDTO.getTotalStock() <= 0) {
            throw new CouponBusinessException("总库存必须大于0");
        }
        if (seckillCouponDTO.getClaimStartTime() == null
                || seckillCouponDTO.getClaimEndTime() == null) {
            throw new CouponBusinessException("领取开始时间和结束时间不能为空");
        }
        if (!seckillCouponDTO.getClaimStartTime().isBefore(seckillCouponDTO.getClaimEndTime())) {
            throw new CouponBusinessException("领取开始时间必须早于结束时间");
        }
    }

    /**
     * 根据ID查询秒杀券，不存在时抛出业务异常
     * @param id 秒杀券ID
     * @return 秒杀券实体
     */
    private SeckillCoupon getCouponOrThrow(Long id) {
        SeckillCoupon seckillCoupon = seckillCouponMapper.getById(id);
        if (seckillCoupon == null) {
            throw new CouponBusinessException("秒杀券不存在");
        }
        return seckillCoupon;
    }
}
