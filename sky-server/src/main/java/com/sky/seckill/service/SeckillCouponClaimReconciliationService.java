package com.sky.seckill.service;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.governance.processor.ClaimReconciliationProcessor;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程6A批量对账入口，只负责筛选候选记录和调度单条处理。
 */
@Service
public class SeckillCouponClaimReconciliationService {

    // 查询已到处理时间或租约过期的失败记录。
    private final SeckillCouponClaimFailureMapper failureMapper;
    // 封装单条记录的抢占、证据判定和结果提交。
    private final ClaimReconciliationProcessor processor;
    // 提供流程6A的批次、等待时间和租约参数。
    private final SeckillCouponClaimReconciliationProperties properties;

    public SeckillCouponClaimReconciliationService(
            SeckillCouponClaimFailureMapper failureMapper,
            ClaimReconciliationProcessor processor,
            SeckillCouponClaimReconciliationProperties properties) {
        this.failureMapper = failureMapper;
        this.processor = processor;
        this.properties = properties;
    }

    // reconciliationService.reconcileBatch()：批量识别待对账失败记录
    // ├─ GovernanceTiming.positiveOrDefault()：校正等待时间和处理租约
    // ├─ GovernanceTiming.boundedBatchSize()：限制批量查询规模
    // ├─ failureMapper.listReconciliationCandidates()：查询已到处理时间或租约过期的记录
    // └─ processor.process()：独立处理一条流程6A记录
    //    ├─ tryClaim()：通过状态CAS取得处理权并写入租约
    //    │  ├─ failureMapper.tryStartPendingReconciliation()：抢占普通待处理记录
    //    │  └─ failureMapper.tryReclaimExpiredReconciliation()：接管租约过期记录
    //    ├─ evaluator.evaluate()：根据MySQL和Redis证据生成治理结论
    //    │  ├─ evidenceInspector.inspectMysqlFacts()：检查领取、用户券和活动事实
    //    │  ├─ evidenceInspector.inspectRedisEvidence()：检查Redis预扣双重证据
    //    │  └─ hasExceededManualThreshold()：判断证据缺失是否已超人工阈值
    //    └─ completeSafely()：携带租约令牌CAS提交结果和后续时间
    //       ├─ resolveNextTime()：计算复查退避时间或立即修复时间
    //       │  └─ GovernanceTiming.calculateBackoff()：生成有上限的指数退避
    //       └─ failureMapper.completeReconciliation()：CAS写入治理结论

    /**
     * 批量扫描待对账记录，返回本次成功抢占的记录数。
     */
    public int reconcileBatch() {
        LocalDateTime now = LocalDateTime.now();
        Duration readyDelay = GovernanceTiming.positiveOrDefault(
                properties.getReadyDelay(), Duration.ofSeconds(30));
        Duration processingLease = GovernanceTiming.positiveOrDefault(
                properties.getProcessingLease(), Duration.ofMinutes(2));
        LocalDateTime leaseExpiredBefore = now.minus(processingLease);
        int batchSize = GovernanceTiming.boundedBatchSize(properties.getBatchSize(), 500);

        List<SeckillCouponClaimFailure> candidates = failureMapper.listReconciliationCandidates(
                now, now.minus(readyDelay), leaseExpiredBefore, batchSize);

        int processedCount = 0;
        for (SeckillCouponClaimFailure candidate : candidates) {
            if (processor.process(candidate, now, leaseExpiredBefore, processingLease)) {
                processedCount++;
            }
        }
        return processedCount;
    }
}
