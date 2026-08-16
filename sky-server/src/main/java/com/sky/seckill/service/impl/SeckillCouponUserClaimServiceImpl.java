package com.sky.seckill.service.impl;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.context.BaseContext;
import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.exception.CouponBusinessException;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import com.sky.seckill.mapper.UserCouponMapper;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.mq.producer.SeckillCouponClaimProducer;
import com.sky.seckill.service.SeckillCouponUserClaimService;
import com.sky.vo.SeckillCouponClaimStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 用户端秒杀券领取用例实现。
 */
@Service
@Slf4j
public class SeckillCouponUserClaimServiceImpl implements SeckillCouponUserClaimService {

    // 用户领取消息当前结构版本。
    private static final int MESSAGE_SCHEMA_VERSION = 1;
    // 执行Redis预扣和RocketMQ事务消息发送的生产者。
    private final SeckillCouponClaimProducer claimProducer;
    // 查询当前用户最终领取事实的数据访问接口。
    private final UserCouponMapper userCouponMapper;
    // 查询当前用户抽象治理状态的数据访问接口。
    private final SeckillCouponClaimFailureMapper failureMapper;

    /**
     * 注入领取消息生产者、最终领取事实和内部治理状态查询能力。
     */
    public SeckillCouponUserClaimServiceImpl(
            SeckillCouponClaimProducer claimProducer,
            UserCouponMapper userCouponMapper,
            SeckillCouponClaimFailureMapper failureMapper) {
        this.claimProducer = claimProducer;
        this.userCouponMapper = userCouponMapper;
        this.failureMapper = failureMapper;
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

    /**
     * 优先以当前用户的MySQL领取记录判定成功，其余只返回抽象处理状态。
     */
    @Override
    public SeckillCouponClaimStatusVO getClaimStatus(String claimId) {
        if (claimId == null || claimId.isBlank() || claimId.length() > 64) {
            throw new CouponBusinessException("领取流水ID不合法");
        }

        Long userId = BaseContext.getCurrentIdOrThrow();
        if (userCouponMapper.getByClaimIdAndUserId(claimId, userId) != null) {
            return buildClaimStatus(claimId, SeckillCouponClaimStatusVO.SUCCESS);
        }

        SeckillCouponClaimFailure failure = failureMapper.getLatestByClaimIdAndUserId(claimId, userId);
        if (failure != null && isTerminalFailure(failure.getStatus())) {
            return buildClaimStatus(claimId, SeckillCouponClaimStatusVO.FAILED);
        }
        // 治理消息可能仍在MQ途中；未知流水也不泄露其他用户是否存在该claimId。
        return buildClaimStatus(claimId, SeckillCouponClaimStatusVO.PROCESSING);
    }

    /**
     * 判断内部治理状态是否已经无法继续自动修复。
     */
    private boolean isTerminalFailure(String status) {
        return SeckillCouponClaimFailure.STATUS_QUARANTINED.equals(status)
                || SeckillCouponClaimFailure.STATUS_MANUAL_REQUIRED.equals(status);
    }

    /**
     * 创建不携带failureId和错误详情的用户侧状态对象。
     */
    private SeckillCouponClaimStatusVO buildClaimStatus(String claimId, String status) {
        return SeckillCouponClaimStatusVO.builder()
                .claimId(claimId)
                .status(status)
                .build();
    }
}
