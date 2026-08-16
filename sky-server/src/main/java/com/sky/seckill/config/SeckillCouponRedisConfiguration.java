package com.sky.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 秒杀券 Lua 脚本配置。
 */
@Configuration
public class SeckillCouponRedisConfiguration {

    /**
     * 加载秒杀券原子预扣Lua脚本并注册为独立Spring Bean。
     *
     * @return 返回Long执行结果的Redis脚本
     */
    @Bean("seckillCouponLuaScript")
    public DefaultRedisScript<Long> seckillCouponLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckillCoupon.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
