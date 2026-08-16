package com.sky.seckill.service;

import com.sky.entity.SeckillCoupon;
import com.sky.entity.UserCoupon;
import com.sky.seckill.mapper.SeckillCouponMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import com.sky.seckill.exception.SeckillCouponClaimPersistenceException;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 流程4-步骤14～16：在Consumer线程中开启独立MySQL事务，幂等落地Redis已经预扣的领取结果。
 */
@Service
@Slf4j
public class SeckillCouponClaimPersistenceService {

    // 将消息时间戳转换为领取业务时间时使用的统一时区。
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");
    // 查询活动并执行MySQL条件扣减库存的数据访问接口。
    private final SeckillCouponMapper seckillCouponMapper;
    // 查询幂等事实、一人一券事实并写入用户券的数据访问接口。
    private final UserCouponMapper userCouponMapper;

    /**
     * 注入秒杀券活动和用户券数据访问接口。
     */
    public SeckillCouponClaimPersistenceService(SeckillCouponMapper seckillCouponMapper, UserCouponMapper userCouponMapper) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    /**
     * 流程4-步骤14～16：消费消息时只落库，不再按消费时刻重新判断活动状态与时间。
     * 同一事务内完成claimId幂等检查、MySQL条件扣库存和用户券插入。
     */
    @Transactional
    public void persist(SeckillCouponClaimMessage message) {
        // 流程4-步骤15（幂等检查）：按claimId查询领取记录。
        UserCoupon persistedCoupon = userCouponMapper.getByClaimId(message.getClaimId());
        if (persistedCoupon != null) {
            if (!message.getCouponId().equals(persistedCoupon.getCouponId()) ||
                    !message.getUserId().equals(persistedCoupon.getUserId())) {
                throw new SeckillCouponClaimPersistenceException(SeckillCouponClaimFailureCode.CLAIM_ID_CONFLICT,
                        "领取流水ID对应的业务数据不一致，claimId=" + message.getClaimId()
                );
            }
            log.info("秒杀券领取消息已落库，跳过重复消费，claimId={}", message.getClaimId());
            return;
        }
        //一人一券约束（用户已领过这张券）
        UserCoupon claimedCoupon = userCouponMapper.getByCouponIdAndUserId(message.getCouponId(), message.getUserId());
        if (claimedCoupon != null) {
            throw new SeckillCouponClaimPersistenceException(
                    SeckillCouponClaimFailureCode.USER_ALREADY_CLAIMED,
                    "用户已经领取该秒杀券，claimId=" + message.getClaimId()
            );
        }

        // 流程4-步骤16：查询秒杀券,保证券存在且领取结束时间非空,并在同一MySQL事务中扣减已经由Redis预扣的数据库库存。
        SeckillCoupon coupon = seckillCouponMapper.getById(message.getCouponId());
        if (coupon == null || coupon.getClaimEndTime() == null) {
            throw new SeckillCouponClaimPersistenceException(
                    SeckillCouponClaimFailureCode.COUPON_NOT_FOUND,
                    "秒杀券不存在或领取结束时间为空，claimId=" + message.getClaimId()
            );
        }
        //条件更新（stock > 0 才扣）,返回受影响行数
        int affectedRows = seckillCouponMapper.decreaseStockAfterPreDeduct(message.getCouponId());
        if (affectedRows != 1) {
            throw new SeckillCouponClaimPersistenceException(
                    SeckillCouponClaimFailureCode.MYSQL_STOCK_CONFLICT,
                    "Redis已预扣但MySQL库存扣减失败，claimId=" + message.getClaimId()
            );
        }

        LocalDateTime claimTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.getClaimedAt()), BUSINESS_ZONE_ID);

        // 流程4-步骤15（最终写入）：库存扣减成功后写入领取记录，claimId保证重复消费幂等。
        userCouponMapper.insert(UserCoupon.builder()
                .claimId(message.getClaimId())
                .couponId(message.getCouponId())
                .userId(message.getUserId())
                .status(UserCoupon.UNUSED)
                .claimTime(claimTime)
                .expireTime(coupon.getClaimEndTime())
                .build());
    }
}
