package com.sky.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.sky.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 展示数据多级缓存：Caffeine为L1，普通Redis为L2，MySQL Supplier为事实回源。
 */
@Service
@Slf4j
public class DisplayCacheService {

    private static final Object NULL_VALUE = new Object();
    private final Cache<String, Object> localCache;
    private final CacheClient cacheClient;
    private final DisplayCacheProperties properties;

    public DisplayCacheService(@Qualifier("displayLocalCache") Cache<String, Object> localCache, CacheClient cacheClient, DisplayCacheProperties properties) {
        this.localCache = localCache;
        this.cacheClient = cacheClient;
        this.properties = properties;
    }

    /**
     * L1未命中时同一个应用实例只执行一次L2/数据库加载。
     */
    @SuppressWarnings("unchecked")
    public <R> R query(String key, Type type, Supplier<R> dbFallback) {
        Objects.requireNonNull(key, "缓存Key不能为空");
        Object value = localCache.get(key, ignored -> loadFromRedis(key, type, dbFallback));
        return value == NULL_VALUE ? null : (R) value;
    }
    //L1未命中加载L2(redis)数据
    private <R> Object loadFromRedis(String key, Type type, Supplier<R> dbFallback) {
        try {
            R value = cacheClient.queryWithLogicalExpire(key, type, dbFallback, properties.getRedisLogicalTtl().getSeconds(), TimeUnit.SECONDS);
            return value == null ? NULL_VALUE : value;
        } catch (RedisConnectionFailureException | RedisConnectionException exception) {
            // Redis不可用时由Caffeine的单Key加载互斥限制本实例只回源一次，避免同机请求击穿数据库。
            log.warn("普通Redis不可用，展示缓存降级查询MySQL，key={}", key);
            R value = dbFallback.get();
            return value == null ? NULL_VALUE : value;
        }
    }

    /**
     * 事务提交后先清L1，再使用与重建相同的锁精确删除L2。
     */
    public void evict(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        localCache.invalidateAll(keys);
        for (String key : keys) {
            try {
                if (!cacheClient.tryDeleteWithMutex(key)) {
                    log.warn("缓存失效未获得重建锁，等待MQ广播或TTL兜底，key={}", key);
                }
            } catch (RuntimeException exception) {
                log.error("Redis展示缓存失效失败，等待MQ广播或TTL兜底，key={}", key, exception);
            }
        }
    }

    /**
     * 只删除当前应用实例L1，用于主动覆盖缓存后避免本机继续读取旧对象。
     */
    public void evictLocal(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            localCache.invalidateAll(keys);
        }
    }
}
