package com.sky.service.impl;

import com.sky.constant.SeckillCouponClaimConstant;
import com.sky.context.BaseContext;
import com.sky.exception.CouponBusinessException;
import com.sky.mq.message.SeckillCouponClaimMessage;
import com.sky.mq.producer.SeckillCouponClaimProducer;
import com.sky.service.SeckillCouponUserClaimService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 用户端秒杀券领取用例实现。
 */
@Service
@Slf4j
public class SeckillCouponUserClaimServiceImpl implements SeckillCouponUserClaimService {

    private static final int MESSAGE_SCHEMA_VERSION = 1;
    private final SeckillCouponClaimProducer claimProducer;

    public SeckillCouponUserClaimServiceImpl(SeckillCouponClaimProducer claimProducer) {
        this.claimProducer = claimProducer;
    }

    /**
     * 流程3-步骤1～3：准备领取消息并调用Producer；步骤12的Lua结果返回后再转换为业务结果。
     */
    @Override
    public String claim(Long couponId) {
        // 步骤1的入口准备：校验请求并取得当前登录用户，尚未进入Redis或MQ。
        if (couponId == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }
        Long userId = BaseContext.getCurrentIdOrThrow();
        String claimId = UUID.randomUUID().toString();

        // 流程3-步骤2：生成跨Producer、Broker和Consumer传递的领取消息，claimId贯穿流程3和流程4。
        SeckillCouponClaimMessage message = SeckillCouponClaimMessage.builder()
                .schemaVersion(MESSAGE_SCHEMA_VERSION)
                .claimId(claimId)
                .couponId(couponId)
                .userId(userId)
                .claimedAt(System.currentTimeMillis())
                .build();

        Long result;
        try {
            // 流程3-步骤3：调用Producer发送事务消息；当前请求会等待步骤7～12的本地事务回调结束。
            result = claimProducer.sendInTransaction(message);
        } catch (RuntimeException exception) {
            log.error("秒杀券领取请求提交失败，claimId={}，couponId={}，userId={}", claimId, couponId, userId, exception);
            throw new CouponBusinessException("领取请求提交失败，请稍后查询券包或重试");
        }

        // 流程3-步骤12的结果回到业务层：Lua成功返回claimId，其他返回码转换为对应业务异常。
        if (SeckillCouponClaimConstant.SUCCESS.equals(result)) {
            return claimId;
        }
        if (SeckillCouponClaimConstant.OUT_OF_STOCK.equals(result)) {
            throw new CouponBusinessException("秒杀券已抢完");
        }
        if (SeckillCouponClaimConstant.DUPLICATE_CLAIM.equals(result)) {
            throw new CouponBusinessException("每位用户限领一张，请勿重复领取");
        }
        if (SeckillCouponClaimConstant.ACTIVITY_NOT_INITIALIZED.equals(result)) {
            throw new CouponBusinessException("秒杀活动数据正在恢复，请稍后重试");
        }
        if (SeckillCouponClaimConstant.ACTIVITY_DISABLED.equals(result)) {
            throw new CouponBusinessException("秒杀活动已停用");
        }
        if (SeckillCouponClaimConstant.ACTIVITY_NOT_STARTED.equals(result)) {
            throw new CouponBusinessException("秒杀活动尚未开始");
        }
        if (SeckillCouponClaimConstant.ACTIVITY_ENDED.equals(result)) {
            throw new CouponBusinessException("秒杀活动已结束");
        }
        throw new CouponBusinessException("秒杀活动状态异常，请稍后重试");
    }
}
