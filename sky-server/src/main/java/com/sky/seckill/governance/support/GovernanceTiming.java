package com.sky.seckill.governance.support;

import java.time.Duration;

/**
 * 流程6共用的次数和时间退避计算。
 */
public final class GovernanceTiming {

    private GovernanceTiming() {
    }

    /**
     * 返回包含本次数据库CAS操作的尝试次数。
     */
    public static int nextAttempt(Integer currentAttempts) {
        if (currentAttempts == null || currentAttempts < 0) {
            return 1;
        }
        return currentAttempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentAttempts + 1;
    }

    /**
     * 空值、零值或负Duration统一回退到安全默认值。
     */
    public static Duration positiveOrDefault(Duration duration, Duration defaultValue) {
        return duration == null || duration.isNegative() || duration.isZero()
                ? defaultValue
                : duration;
    }

    /**
     * 把批量配置限制在1到业务允许的上限之间。
     */
    public static int boundedBatchSize(int configuredSize, int maximumSize) {
        return Math.min(Math.max(configuredSize, 1), Math.max(maximumSize, 1));
    }

    /**
     * 根据尝试次数计算带上限的指数退避时间。
     */
    public static Duration calculateBackoff(Duration configuredInitial,
                                            Duration configuredMaximum,
                                            int attempt,
                                            Duration defaultInitial,
                                            Duration defaultMaximum) {
        Duration initial = positiveOrDefault(configuredInitial, defaultInitial);
        Duration maximum = positiveOrDefault(configuredMaximum, defaultMaximum);
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        long initialMillis = Math.max(initial.toMillis(), 1L);
        long maximumMillis = Math.max(maximum.toMillis(), initialMillis);
        long calculatedMillis = initialMillis > Long.MAX_VALUE / multiplier
                ? maximumMillis
                : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(calculatedMillis, maximumMillis));
    }
}
