package com.sky.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 流程6内部对账任务配置，本地学习环境与部署环境使用同一套业务参数。
 * 配置访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@Component
@ConfigurationProperties(prefix = "sky.seckill-coupon.claim-reconciliation")
@Data
public class SeckillCouponClaimReconciliationProperties {

    // 是否启用流程6定时治理任务。
    private boolean enabled = true;
    // 流程6A/B单批最多处理的失败记录数。
    private int batchSize = 50;
    // 最终失败进入单笔对账前的安全等待时间。
    private Duration readyDelay = Duration.ofSeconds(30);
    // 多实例抢占单条任务时使用的处理租约时长。
    private Duration processingLease = Duration.ofMinutes(2);
    // Redis证据持续缺失后转人工治理的最长等待时间。
    private Duration manualAfter = Duration.ofMinutes(30);
    // 流程6A首次复查退避时间。
    private Duration recheckInitialBackoff = Duration.ofSeconds(30);
    // 流程6A复查退避时间上限。
    private Duration recheckMaxBackoff = Duration.ofMinutes(10);
    // 流程6B允许的最大自动修复次数。
    private int maxRepairAttempts = 5;
    // 流程6B首次修复重试退避时间。
    private Duration repairInitialBackoff = Duration.ofSeconds(30);
    // 流程6B修复重试退避时间上限。
    private Duration repairMaxBackoff = Duration.ofMinutes(10);
    // 流程6C单批最多处理的活动数。
    private int settlementBatchSize = 20;
    // 活动结束后进入总账核对前的安全等待时间。
    private Duration settlementReadyDelay = Duration.ofMinutes(10);
    // 活动总账持续不一致后转人工治理的最长等待时间。
    private Duration settlementManualAfter = Duration.ofHours(2);
    // 流程6C首次复查退避时间。
    private Duration settlementInitialBackoff = Duration.ofMinutes(1);
    // 流程6C复查退避时间上限。
    private Duration settlementMaxBackoff = Duration.ofMinutes(15);
    // 活动结束后默认保留Redis领取证据的时间。
    private Duration evidenceRetention = Duration.ofDays(7);
    // 活动总账一致后继续保留Redis证据的追查时间。
    private Duration settledEvidenceRetention = Duration.ofDays(1);
}
