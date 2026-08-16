package com.sky.seckill.mq.transaction;

import com.sky.seckill.constant.SeckillCouponClaimConstant;
import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import com.sky.seckill.redis.SeckillCouponRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;

/**
 * 流程3事务消息本地事务与Broker回查监听器。
 */
@RocketMQTransactionListener//事务开启自动执行
@Slf4j
public class SeckillCouponClaimTransactionListener implements RocketMQLocalTransactionListener {

    // 执行Redis Lua预扣并提供事务回查证据的仓储。
    private final SeckillCouponRedisRepository redisRepository;

    /**
     * 创建领取事务监听器并注入Redis仓储。
     *
     * @param redisRepository 秒杀领取Redis仓储
     */
    public SeckillCouponClaimTransactionListener(SeckillCouponRedisRepository redisRepository) {
        this.redisRepository = redisRepository;
    }

    /**
     * 流程3-步骤7～11：RocketMQ同步回调本地事务，读取Context消息、执行Lua并把结果写回Context。
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object argument) {
        if (!(argument instanceof SeckillCouponClaimTransactionContext transactionContext)) {
            log.error("秒杀券领取事务消息缺少本地事务上下文");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        // 流程3-步骤8：从步骤4创建的同一个Context中取得强类型领取消息，避免重新解析byte[]消息体。
        SeckillCouponClaimMessage claimMessage = transactionContext.getClaimMessage();
        try {
            // 流程3-步骤9：调用RedisRepository进入Lua原子预扣。
            Long result = redisRepository.tryPreDeduct(claimMessage.getCouponId(), claimMessage.getUserId(), claimMessage.getClaimId());

            // 流程3-步骤11：Lua返回Long结果后回写Context，供Producer在步骤12读取。
            transactionContext.setPreDeductResult(result);
            return SeckillCouponClaimConstant.SUCCESS.equals(result) ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
        } catch (RuntimeException exception) {
            log.error("秒杀券Lua预扣异常，等待Broker回查，claimId={}", claimMessage.getClaimId(), exception);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 异常分支：Broker无法确认步骤7的结果时异步回查，不依赖只存在于原发送线程中的Context。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        Object claimIdHeader = message.getHeaders().get(SeckillCouponClaimMessage.CLAIM_ID_HEADER);
        Object couponIdHeader = message.getHeaders().get(SeckillCouponClaimMessage.COUPON_ID_HEADER);
        Object userIdHeader = message.getHeaders().get(SeckillCouponClaimMessage.USER_ID_HEADER);
        if (claimIdHeader == null || String.valueOf(claimIdHeader).isBlank() || couponIdHeader == null || userIdHeader == null) {
            log.error("秒杀券事务回查消息头不完整，headers={}", message.getHeaders());
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        String claimId = String.valueOf(claimIdHeader);
        Long couponId;
        Long userId;
        try {
            couponId = Long.valueOf(String.valueOf(couponIdHeader));
            userId = Long.valueOf(String.valueOf(userIdHeader));
        } catch (NumberFormatException exception) {
            log.error("秒杀券事务回查消息头格式错误，claimId={}，couponId={}，userId={}", claimId, couponIdHeader, userIdHeader);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            // 回查只依赖消息Header和Redis中的claimId记录，可在稍后或其他Producer实例上执行。
            return redisRepository.isClaimPreDeducted(couponId, userId, claimId) ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
        } catch (RuntimeException exception) {
            log.error("秒杀券事务状态回查失败，claimId={}", claimId, exception);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
