package com.sky.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 普通展示数据的Caffeine本地缓存配置。
 */
@Configuration
@EnableConfigurationProperties(DisplayCacheProperties.class)
public class DisplayCacheConfiguration {

    @Bean("displayLocalCache")
    public Cache<String, Object> displayLocalCache(DisplayCacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.getLocalMaximumSize())
                .expireAfterWrite(properties.getLocalTtl())
                .recordStats()
                .build();
    }
}
