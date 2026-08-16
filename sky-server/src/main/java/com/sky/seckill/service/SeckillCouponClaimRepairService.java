package com.sky.seckill.service;

import com.sky.entity.SeckillCouponClaimFailure;
import com.sky.seckill.config.SeckillCouponClaimReconciliationProperties;
import com.sky.seckill.governance.processor.ClaimRepairProcessor;
import com.sky.seckill.governance.support.GovernanceTiming;
import com.sky.seckill.mapper.SeckillCouponClaimFailureMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程6B批量修复入口，只负责筛选已获准修复的候选记录。
 */
@Service
public class SeckillCouponClaimRepairService {

    // 查询已到修复时间或租约过期的失败记录。
    private final SeckillCouponClaimFailureMapper failureMapper;
    // 封装单条记录的抢占、受控修复和结果提交。
    private final ClaimRepairProcessor processor;
    // 提供流程6B的批次和处理租约参数。
    private final SeckillCouponClaimReconciliationProperties properties;

    public SeckillCouponClaimRepairService(
            SeckillCouponClaimFailureMapper failureMapper,
            ClaimRepairProcessor processor,
            SeckillCouponClaimReconciliationProperties properties) {
        this.failureMapper = failureMapper;
        this.processor = processor;
        this.properties = properties;
    }

    // repairService.repairBatch()：批量处理已获准修复的失败记录
    // ├─ GovernanceTiming.positiveOrDefault()：校正处理租约时长
    // ├─ GovernanceTiming.boundedBatchSize()：限制批量查询规模
    // ├─ failureMapper.listRepairCandidates()：查询已到修复时间或租约过期的记录
    // └─ processor.process()：独立处理一条流程6B记录
    //    ├─ tryClaim()：通过状态CAS取得修复权并写入租约
    //    │  ├─ failureMapper.tryStartPendingRepair()：抢占普通待修复记录
    //    │  └─ failureMapper.tryReclaimExpiredRepair()：接管租约过期的修复记录
    //    ├─ executor.execute()：重验强证据并分类修复异常
    //    │  ├─ persistAfterValidation()：复用流程4事务补落库并二次确认
    //    │  │  ├─ evidenceInspector.inspectMysqlFacts()：确认MySQL尚需且允许补写
    //    │  │  ├─ messageInspector.inspect()：还原并校验原始领取消息
    //    │  │  ├─ evidenceInspector.inspectRedisEvidence()：再次确认Redis预扣双重证据
    //    │  │  ├─ persistenceService.persist()：执行原幂等扣库存和发券事务
    //    │  │  └─ userCouponMapper.getByClaimId()：查询并确认最终落库事实
    //    │  ├─ resolveDuplicateRace()：按最终事实处理唯一键竞争
    //    │  └─ classifyDataAccessFailure()：区分可重试的瞬时故障与永久故障
    //    └─ completeSafely()：携带租约令牌CAS提交结果和重试计划
    //       ├─ nextRepairTime()：计算下一次受控修复退避时间
    //       └─ failureMapper.completeRepair()：CAS写入修复结论

    /**
     * 批量扫描待修复记录，返回本次成功抢占的记录数。
     */
    public int repairBatch() {
        LocalDateTime now = LocalDateTime.now();
        Duration processingLease = GovernanceTiming.positiveOrDefault(
                properties.getProcessingLease(), Duration.ofMinutes(2));
        LocalDateTime leaseExpiredBefore = now.minus(processingLease);
        int batchSize = GovernanceTiming.boundedBatchSize(properties.getBatchSize(), 500);

        List<SeckillCouponClaimFailure> candidates = failureMapper.listRepairCandidates(
                now, leaseExpiredBefore, batchSize);

        int processedCount = 0;
        for (SeckillCouponClaimFailure candidate : candidates) {
            if (processor.process(candidate, now, leaseExpiredBefore, processingLease)) {
                processedCount++;
            }
        }
        return processedCount;
    }
}
