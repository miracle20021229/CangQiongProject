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
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis连接与模板配置。
 * 普通展示缓存使用6379连接，秒杀库存和领取证据使用独立6380连接。
 */
@Configuration
@EnableConfigurationProperties(SeckillRedisProperties.class)
public class RedisConfiguration {

    /**
     * 6379普通缓存连接工厂，作为未指定Qualifier时的默认连接。
     */
    @Bean("cacheRedisConnectionFactory")
    @Primary
    public RedisConnectionFactory cacheRedisConnectionFactory(@Qualifier("redisson") RedissonClient redissonClient) {
        return new RedissonConnectionFactory(redissonClient);
    }

    /**
     * 6380秒杀状态连接工厂，与可淘汰的普通缓存物理隔离。
     */
    @Bean("seckillRedisConnectionFactory")
    public LettuceConnectionFactory seckillRedisConnectionFactory(SeckillRedisProperties properties) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(properties.getHost());
        configuration.setPort(properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        configuration.setPassword(RedisPassword.of(properties.getPassword()));
        return new LettuceConnectionFactory(configuration);
    }

    /**
     * 普通缓存字符串模板；通过名称限定，避免与通用RedisTemplate争抢主Bean。
     */
    @Bean(name = {"stringRedisTemplate", "cacheStringRedisTemplate"})
    public StringRedisTemplate cacheStringRedisTemplate(@Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 秒杀Lua、库存、领取用户集合和claim记录专用模板。
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
