package com.sky.seckill.policy;

import com.sky.seckill.enums.SeckillCouponClaimFailureCode;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * 流程5选择性重试白名单。
 * 未知异常和非瞬时数据库错误默认不重试，避免错误风暴。
 */
@Component
public class SeckillCouponClaimRetryPolicy {

    // 唯一键竞争最多允许由Broker额外重试的次数。
    private static final int MAX_DUPLICATE_KEY_RETRIES = 1;

    /**
     * 判断数据库异常是否属于网络闪断、连接耗尽或死锁等瞬时故障。
     * 瞬时故障交给RocketMQ延迟重投，非瞬时故障直接隔离，避免无效重试放大故障。
     *
     * @param exception Spring数据访问异常
     * @return 对应的稳定失败码
     */
    public SeckillCouponClaimFailureCode classify(DataAccessException exception) {
        return isTransient(exception)
                ? SeckillCouponClaimFailureCode.MYSQL_TRANSIENT_FAILURE
                : SeckillCouponClaimFailureCode.MYSQL_NON_TRANSIENT_FAILURE;
    }

    /**
     * 根据当前重复消费次数区分短暂唯一键竞争和稳定业务冲突。
     *
     * @param reconsumeTimes RocketMQ已经重复消费的次数
     * @return 唯一键竞争或唯一键冲突失败码
     */
    public SeckillCouponClaimFailureCode classifyDuplicateKey(int reconsumeTimes) {
        return reconsumeTimes < MAX_DUPLICATE_KEY_RETRIES
                ? SeckillCouponClaimFailureCode.MYSQL_DUPLICATE_RACE
                : SeckillCouponClaimFailureCode.MYSQL_DUPLICATE_CONFLICT;
    }

    /**
     * 判断异常本身或其JDBC原因链是否属于可恢复的瞬时异常家族。
     *
     * @param exception Spring数据访问异常
     * @return 属于瞬时异常时返回true
     */
    private boolean isTransient(DataAccessException exception) {
        return exception instanceof TransientDataAccessException
                || exception instanceof RecoverableDataAccessException
                || exception instanceof DataAccessResourceFailureException
                || hasSqlTransientCause(exception);
    }

    /**
     * 沿异常原因链查找JDBC瞬时异常，兼容被Spring再次包装的情况。
     *
     * @param throwable 待检查的异常
     * @return 原因链含瞬时或可恢复JDBC异常时返回true
     */
    private boolean hasSqlTransientCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
