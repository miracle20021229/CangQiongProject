package com.sky.config;

import com.sky.seckill.config.SeckillRedisProperties;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Redis 连接与模板配置。
 * 普通展示缓存继续使用 6379；秒杀库存和领取证据通过 Sentinel 访问独立主从组。
 */
@Configuration
@EnableConfigurationProperties(SeckillRedisProperties.class)
public class RedisConfiguration {

    /**
     * 6379 普通缓存连接工厂，作为未指定 Qualifier 时的默认连接。
     */
    @Bean("cacheRedisConnectionFactory")
    @Primary
    public RedisConnectionFactory cacheRedisConnectionFactory(@Qualifier("redisson") RedissonClient redissonClient) {
        return new RedissonConnectionFactory(redissonClient);
    }

    /**
     * 秒杀 Redis 连接交接：Sentinel 返回当前主节点，Lettuce 在故障切换后重新建连。
     */
    @Bean("seckillRedisConnectionFactory")
    public LettuceConnectionFactory seckillRedisConnectionFactory(SeckillRedisProperties properties) {
        SeckillRedisProperties.Sentinel sentinel = properties.getSentinel();
        List<String> nodes = sentinel == null ? null : sentinel.getNodes();
        if (sentinel != null && StringUtils.hasText(sentinel.getMaster())
                && nodes != null && !nodes.isEmpty()) {
            RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
            configuration.master(sentinel.getMaster());
            for (String node : nodes) {
                String address = node == null ? "" : node.trim();
                int separator = address.lastIndexOf(':');
                if (separator <= 0 || separator == address.length() - 1) {
                    throw new IllegalArgumentException("非法 Sentinel 地址: " + node);
                }
                configuration.sentinel(
                        address.substring(0, separator),
                        Integer.parseInt(address.substring(separator + 1)));
            }
            configuration.setDatabase(properties.getDatabase());
            if (StringUtils.hasText(properties.getPassword())) {
                configuration.setPassword(RedisPassword.of(properties.getPassword()));
            }
            return new LettuceConnectionFactory(configuration);
        }

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(properties.getHost());
        configuration.setPort(properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        if (StringUtils.hasText(properties.getPassword())) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
        return new LettuceConnectionFactory(configuration);
    }

    /**
     * 普通缓存字符串模板；通过名称限定，避免与通用 RedisTemplate 争抢 Bean。
     */
    @Bean(name = {"stringRedisTemplate", "cacheStringRedisTemplate"})
    public StringRedisTemplate cacheStringRedisTemplate(@Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 秒杀 Lua、库存、领取用户集合和 claim 记录专用模板。
     */
    @Bean("seckillStringRedisTemplate")
    public StringRedisTemplate seckillStringRedisTemplate(@Qualifier("seckillRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 兼容菜品、店铺等现有普通缓存调用。
     */
    @Bean("redisTemplate")
    @Primary
    public RedisTemplate<Object, Object> redisTemplate(@Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisTemplate<Object, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
