package com.sky.config;

import com.sky.seckill.config.SeckillRedisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证普通缓存与秒杀状态使用不同Redis连接，防止后续配置回退到单实例。
 */
class RedisConfigurationTests {

    /**
     * 验证普通缓存模板与秒杀状态模板分别绑定6379和6380连接工厂。
     */
    @Test
    void shouldRouteCacheAndSeckillTemplatesToDifferentFactories() {
        RedisConfiguration redisConfiguration = new RedisConfiguration();
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisConnectionFactory cacheFactory = redisConfiguration.cacheRedisConnectionFactory(redissonClient);
        assertTrue(cacheFactory instanceof RedissonConnectionFactory);

        SeckillRedisProperties seckillProperties = new SeckillRedisProperties();
        seckillProperties.setHost("127.0.0.1");
        seckillProperties.setPort(6380);
        seckillProperties.setPassword("123456");
        seckillProperties.setDatabase(0);
        LettuceConnectionFactory seckillFactory =
                redisConfiguration.seckillRedisConnectionFactory(seckillProperties);

        StringRedisTemplate cacheTemplate = redisConfiguration.cacheStringRedisTemplate(cacheFactory);
        StringRedisTemplate seckillTemplate = redisConfiguration.seckillStringRedisTemplate(seckillFactory);
        RedisTemplate<?, ?> redisTemplate = redisConfiguration.redisTemplate(cacheFactory);

        assertSame(cacheFactory, cacheTemplate.getConnectionFactory());
        assertSame(cacheFactory, redisTemplate.getConnectionFactory());
        assertSame(seckillFactory, seckillTemplate.getConnectionFactory());
        assertEquals("127.0.0.1", seckillFactory.getStandaloneConfiguration().getHostName());
        assertEquals(6380, seckillFactory.getStandaloneConfiguration().getPort());
        assertEquals(0, seckillFactory.getStandaloneConfiguration().getDatabase());
        assertArrayEquals("123456".toCharArray(),
                seckillFactory.getStandaloneConfiguration().getPassword().get());
    }

    /**
     * 本机双实例验收按需执行，默认构建不依赖外部Redis服务。
     */
    @Test
    @EnabledIfSystemProperty(named = "sky.redis.integration", matches = "true")
    void shouldRouteRealWritesToTwoLocalInstances() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedissonAutoConfigurationV2.class,
                        RedisAutoConfiguration.class))
                .withUserConfiguration(RedisConfiguration.class)
                .withPropertyValues(
                        "spring.redis.host=127.0.0.1",
                        "spring.redis.port=6379",
                        "spring.redis.password=123456",
                        "spring.redis.database=0",
                        "sky.redis.seckill.host=127.0.0.1",
                        "sky.redis.seckill.port=6380",
                        "sky.redis.seckill.password=123456",
                        "sky.redis.seckill.database=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, context.getBeansOfType(RedisConnectionFactory.class).size());
                    assertEquals(1, context.getBeansOfType(RedissonClient.class).size());

                    StringRedisTemplate cacheTemplate = context.getBean(
                            "cacheStringRedisTemplate", StringRedisTemplate.class);
                    StringRedisTemplate seckillTemplate = context.getBean(
                            "seckillStringRedisTemplate", StringRedisTemplate.class);
                    RedissonClient redissonClient = context.getBean(RedissonClient.class);
                    assertSame(redissonClient, context.getBean("redisson"));
                    assertEquals("redis://127.0.0.1:6379",
                            redissonClient.getConfig().useSingleServer().getAddress());
                    assertEquals("123456", redissonClient.getConfig().useSingleServer().getPassword());

                    String suffix = UUID.randomUUID().toString();
                    String cacheKey = "test:redis-routing:cache:" + suffix;
                    String seckillKey = "test:redis-routing:seckill:" + suffix;
                    try {
                        cacheTemplate.opsForValue().set(cacheKey, "cache-ok", Duration.ofMinutes(1));
                        seckillTemplate.opsForValue().set(seckillKey, "seckill-ok", Duration.ofMinutes(1));

                        assertEquals("cache-ok", cacheTemplate.opsForValue().get(cacheKey));
                        assertEquals("seckill-ok", seckillTemplate.opsForValue().get(seckillKey));
                        assertFalse(Boolean.TRUE.equals(cacheTemplate.hasKey(seckillKey)));
                        assertFalse(Boolean.TRUE.equals(seckillTemplate.hasKey(cacheKey)));
                    } finally {
                        cacheTemplate.delete(cacheKey);
                        seckillTemplate.delete(seckillKey);
                    }
                });
    }
}
