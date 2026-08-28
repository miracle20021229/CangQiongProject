package com.sky.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 普通展示数据的L1/L2缓存参数。
 */
@ConfigurationProperties(prefix = "sky.cache.multilevel")
@Data
public class DisplayCacheProperties {

    // 每个应用实例的Caffeine最大Key数量，防止本地缓存无限占用堆内存。
    private long localMaximumSize = 1024L;
    // L1只保留很短时间，MQ失效消息丢失时也能快速自愈。
    private Duration localTtl = Duration.ofSeconds(30);
    // L2逻辑过期后先返回旧值，再由后台线程异步重建。
    private Duration redisLogicalTtl = Duration.ofMinutes(5);
}
