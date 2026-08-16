package com.sky.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀正确性状态专用Redis实例配置。
 * 配置访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@ConfigurationProperties(prefix = "sky.redis.seckill")
@Data
public class SeckillRedisProperties {

    // 秒杀正确性状态Redis主机地址。
    private String host = "localhost";
    // 秒杀正确性状态Redis端口。
    private int port = 6380;
    // 秒杀正确性状态Redis访问密码。
    private String password;
    // 秒杀正确性状态Redis逻辑库编号。
    private int database = 0;
}
