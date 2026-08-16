package com.sky.seckill.policy;

import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证流程5选择性重试白名单只放行可恢复的瞬时故障。
 */
class SeckillCouponClaimRetryPolicyTests {

    // 被测的领取失败重试分类策略。
    private final SeckillCouponClaimRetryPolicy retryPolicy = new SeckillCouponClaimRetryPolicy();

    /**
     * 验证临时数据库连接故障被分类为可重试失败。
     */
    @Test
    void shouldRetryTemporaryDatabaseConnectionFailure() {
        assertEquals(
                SeckillCouponClaimFailureCode.MYSQL_TRANSIENT_FAILURE,
                retryPolicy.classify(new DataAccessResourceFailureException("temporary unavailable"))
        );
    }

    /**
     * 验证数据完整性等非瞬时数据库故障不会进入Broker重试。
     */
    @Test
    void shouldNotRetryNonTransientDatabaseFailure() {
        assertEquals(
                SeckillCouponClaimFailureCode.MYSQL_NON_TRANSIENT_FAILURE,
                retryPolicy.classify(new DataIntegrityViolationException("invalid data"))
        );
    }

    /**
     * 验证唯一键竞争只允许一次延迟重试，之后转入一致性治理。
     */
    @Test
    void shouldRetryDuplicateRaceOnlyOnce() {
        assertEquals(
                SeckillCouponClaimFailureCode.MYSQL_DUPLICATE_RACE,
                retryPolicy.classifyDuplicateKey(0)
        );
        assertEquals(
                SeckillCouponClaimFailureCode.MYSQL_DUPLICATE_CONFLICT,
                retryPolicy.classifyDuplicateKey(1)
        );
    }
}
