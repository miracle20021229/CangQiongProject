package com.sky.utils;

import com.alibaba.fastjson2.JSON;
import com.sky.exception.BaseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis逻辑过期缓存客户端。
 * 查询时兼顾空值防穿透、冷缓存互斥初始化和过期缓存异步重建。
 */
@Component
@Slf4j
public class CacheClient {

    private static final long CACHE_NULL_TTL_MINUTES = 2L;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final Executor cacheRebuildExecutor;
    private final long mutexLockWaitMillis;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient,
                       @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor,
                       @Value("${sky.cache.mutex-lock-wait-millis:500}") long mutexLockWaitMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.mutexLockWaitMillis = mutexLockWaitMillis;
    }

    /**
     * 逻辑过期写入：用RedisData包装业务数据和逻辑过期时间，不设物理TTL。
     * 用于事务提交后强制刷新缓存。
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(addJitter(time))));
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(redisData));
    }

    /**
     * 启动预热：加锁+双重检查后重建逻辑过期缓存。
     * 多实例同时启动时只有一个实例查库，其他实例等待后读到新缓存直接返回。
     * 拿不到锁且缓存仍不可用时返回null，不抛异常，避免阻断启动流程。
     */
    public <R> R warmUpWithLogicalExpire(String key, Type type, Supplier<R> dbFallback, Long time, TimeUnit unit) {
        RLock lock = redissonClient.getLock("lock:" + key);
        boolean locked;
        try {
            locked = lock.tryLock(mutexLockWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("预热缓存时线程被中断，key={}", key, exception);
            return null;
        }
        if (!locked) {
            // 其他实例正在重建，读取当前缓存返回，不抛异常避免阻断启动
            return readLogicalExpireCache(key, type);
        }
        try {
            return rebuildLogicalExpireCache(key, type, dbFallback, time, unit);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 查询逻辑过期缓存：未过期直接返回，已过期返回旧数据并异步重建。
     * Key丢失或格式损坏时走冷缓存互斥初始化。
     */
    public <R> R queryWithLogicalExpire(String key, Type type, Supplier<R> dbFallback, Long time, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return initializeLogicalExpireCache(key, type, dbFallback, time, unit);
        }
        if (StringUtils.isBlank(json)) {
            return null;
        }

        RedisData redisData;
        try {
            redisData = JSON.parseObject(json, RedisData.class);
        } catch (RuntimeException exception) {
            log.warn("逻辑过期缓存格式无效，将重新初始化，key={}", key);
            return initializeLogicalExpireCache(key, type, dbFallback, time, unit);
        }
        if (redisData == null || redisData.getExpireTime() == null) {
            return initializeLogicalExpireCache(key, type, dbFallback, time, unit);
        }

        R oldData = JSON.parseObject(JSON.toJSONString(redisData.getData()), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return oldData;
        }

        submitCacheRebuild(key, type, dbFallback, time, unit);
        return oldData;
    }

    /**
     * 冷缓存互斥初始化：Key丢失或格式损坏时加锁，只允许一个请求查询数据库。
     * 锁超时后复查缓存，仍不可用则快速失败，避免请求线程无限阻塞。
     */
    private <R> R initializeLogicalExpireCache(String key, Type type, Supplier<R> dbFallback,
                                               Long time, TimeUnit unit) {
        RLock lock = redissonClient.getLock("lock:" + key);
        boolean locked;
        try {
            locked = lock.tryLock(mutexLockWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BaseException("请求已中断，请稍后重试");
        }

        if (!locked) {
            String latestJson = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isNotBlank(latestJson)) {
                RedisData latestRedisData = JSON.parseObject(latestJson, RedisData.class);
                return JSON.parseObject(JSON.toJSONString(latestRedisData.getData()), type);
            }
            if (latestJson != null) {
                return null;
            }
            throw new BaseException("系统繁忙，请稍后重试");
        }

        try {
            return rebuildLogicalExpireCache(key, type, dbFallback, time, unit);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 提交异步重建任务，锁由后台线程获取并释放。
     */
    private <R> void submitCacheRebuild(String key, Type type, Supplier<R> dbFallback,
                                        Long time, TimeUnit unit) {
        RLock lock = redissonClient.getLock("lock:" + key);
        try {
            cacheRebuildExecutor.execute(() -> {
                if (!lock.tryLock()) {
                    return;
                }
                try {
                    rebuildLogicalExpireCache(key, type, dbFallback, time, unit);
                } catch (RuntimeException exception) {
                    log.error("逻辑过期缓存重建失败，key={}", key, exception);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("逻辑过期缓存重建任务被拒绝，key={}", key);
        }
    }

    /**
     * 获得锁后双重检查，缓存仍不可用才查询数据库并写入Redis。
     * 双重检查命中未过期缓存时直接返回，避免重复查库。
     */
    private <R> R rebuildLogicalExpireCache(String key, Type type, Supplier<R> dbFallback,
                                            Long time, TimeUnit unit) {
        String latestJson = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isNotBlank(latestJson)) {
            try {
                RedisData latestRedisData = JSON.parseObject(latestJson, RedisData.class);
                if (latestRedisData != null && latestRedisData.getExpireTime() != null
                        && latestRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return JSON.parseObject(JSON.toJSONString(latestRedisData.getData()), type);
                }
            } catch (RuntimeException exception) {
                log.warn("逻辑过期缓存格式无效，将重新写入，key={}", key);
            }
        } else if (latestJson != null) {
            return null;
        }

        R freshData = dbFallback.get();
        if (freshData == null) {
            cacheNull(key);
        } else {
            setWithLogicalExpire(key, freshData, time, unit);
        }
        return freshData;
    }

    /**
     * 读取当前逻辑过期缓存，不触发重建。用于拿不到锁时的降级读取。
     * 缓存缺失、空值标记或格式无效时统一返回null。
     */
    private <R> R readLogicalExpireCache(String key, Type type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            RedisData redisData = JSON.parseObject(json, RedisData.class);
            if (redisData == null || redisData.getExpireTime() == null) {
                return null;
            }
            return JSON.parseObject(JSON.toJSONString(redisData.getData()), type);
        } catch (RuntimeException exception) {
            log.warn("读取逻辑过期缓存格式无效，key={}", key);
            return null;
        }
    }

    /**
     * 缓存空值，防止缓存穿透
     */
    private void cacheNull(String key) {
        stringRedisTemplate.opsForValue().set(key, "", addJitter(CACHE_NULL_TTL_MINUTES), TimeUnit.MINUTES);
    }

    /**
     * 给原过期时间增加0～10%的随机值，避免大量缓存同时失效造成缓存雪崩。
     * @param time 原过期时间，单位由调用方传入的TimeUnit决定
     * @return 增加随机抖动后的过期时间
     */
    private long addJitter(Long time) {
        long maxJitter = Math.max(1L, time / 10L);
        return time + ThreadLocalRandom.current().nextLong(maxJitter + 1L);
    }
}
