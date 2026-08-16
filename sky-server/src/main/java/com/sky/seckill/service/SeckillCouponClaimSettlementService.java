package com.sky.seckill.service;

import com.sky.entity.SeckillCouponClaimSettlement;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.governance.processor.ClaimSettlementProcessor;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.mapper.SeckillCouponClaimSettlementMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程6C批量结算入口，只负责创建任务、筛选活动和调度单条处理。
 */
@Service
public class SeckillCouponClaimSettlementService {

    // 创建并查询已到结算时间或租约过期的活动记录。
    private final SeckillCouponClaimSettlementMapper settlementMapper;
    // 封装单个活动的抢占、总账核对和结果提交。
    private final ClaimSettlementProcessor processor;
    // 提供流程6C的安全窗口、批次和处理租约参数。
    private final SeckillCouponClaimReconciliationProperties properties;

    public SeckillCouponClaimSettlementService(
            SeckillCouponClaimSettlementMapper settlementMapper,
            ClaimSettlementProcessor processor,
            SeckillCouponClaimReconciliationProperties properties) {
        this.settlementMapper = settlementMapper;
        this.processor = processor;
        this.properties = properties;
    }

    // settlementService.settleBatch()：批量核对已结束活动的最终总账
    // ├─ GovernanceTiming.positiveOrDefault()：校正结算等待时间和处理租约
    // ├─ GovernanceTiming.boundedBatchSize()：限制批量查询规模
    // ├─ settlementMapper.createPendingForEndedCoupons()：为到期活动创建待结算记录
    // ├─ settlementMapper.listCandidates()：查询已到结算时间或租约过期的活动
    // └─ processor.process()：独立处理一个流程6C活动
    //    ├─ tryClaim()：通过状态CAS取得结算权并写入租约
    //    │  ├─ settlementMapper.tryStart()：抢占普通待结算活动
    //    │  └─ settlementMapper.tryReclaimExpired()：接管租约过期活动
    //    ├─ evaluator.evaluate()：读取MySQL、Redis和治理总账生成结论
    //    │  ├─ couponMapper.getById()：读取活动库存和结束时间
    //    │  ├─ userCouponMapper.countByCouponId()：统计MySQL最终发券数量
    //    │  ├─ failureMapper.countUnresolvedByCouponId()：统计未解决治理记录
    //    │  ├─ redisRepository.readSettlementEvidence()：原子读取Redis库存和领取数
    //    │  ├─ Snapshot.isConsistent()：校验多方总账关系是否全部成立
    //    │  └─ hasExceededManualThreshold()：判断总账冲突是否已超人工阈值
    //    ├─ redisRepository.scheduleEvidenceCleanup()：总账一致后延迟清理证据
    //    └─ completeSafely()：CAS持久化总账快照、结论和复查计划
    //       ├─ nextReconcileTime()：计算不一致总账的下次退避时间
    //       ├─ Snapshot.applyTo()：复制事实快照到结算记录
    //       └─ settlementMapper.complete()：CAS持久化活动结算结果

    /**
     * 发现已超过安全窗口的结束活动，并返回本次成功抢占的活动数。
     */
    public int settleBatch() {
        LocalDateTime now = LocalDateTime.now();
        Duration readyDelay = GovernanceTiming.positiveOrDefault(
                properties.getSettlementReadyDelay(), Duration.ofMinutes(10));
        Duration processingLease = GovernanceTiming.positiveOrDefault(
                properties.getProcessingLease(), Duration.ofMinutes(2));
        int batchSize = GovernanceTiming.boundedBatchSize(
                properties.getSettlementBatchSize(), 200);

        settlementMapper.createPendingForEndedCoupons(now.minus(readyDelay), batchSize);
        LocalDateTime leaseExpiredBefore = now.minus(processingLease);
        List<SeckillCouponClaimSettlement> candidates = settlementMapper.listCandidates(
                now, leaseExpiredBefore, batchSize);

        int processedCount = 0;
        for (SeckillCouponClaimSettlement candidate : candidates) {
            if (processor.process(candidate, now, leaseExpiredBefore, processingLease)) {
                processedCount++;
            }
        }
        return processedCount;
    }
}
