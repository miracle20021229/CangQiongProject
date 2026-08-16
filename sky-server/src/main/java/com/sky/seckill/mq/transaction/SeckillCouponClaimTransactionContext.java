package com.sky.seckill.mq.transaction;

import com.sky.seckill.mq.message.SeckillCouponClaimMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 事务消息发送线程与本地事务监听器之间传递的上下文。
 * 流程3-步骤4由Producer创建，步骤8由Listener读取消息，步骤11回写Lua结果，步骤12再由Producer读取结果。
 * {@link RequiredArgsConstructor}会为final字段生成构造方法，因此源码中不再手写构造器。
 */
@Getter
@RequiredArgsConstructor
public class SeckillCouponClaimTransactionContext {

    /**
     * 流程3-步骤4传入、步骤8读取；这里只保存原消息对象的引用，不会复制或发送Context到Broker。
     */
    private final SeckillCouponClaimMessage claimMessage;

    /**
     * 流程3-步骤11由Listener在Lua返回后写入，步骤12由等待事务发送结果的Producer读取。
     */
    @Setter
    private Long preDeductResult;
}
