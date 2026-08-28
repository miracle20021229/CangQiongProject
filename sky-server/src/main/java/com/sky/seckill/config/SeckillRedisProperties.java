package com.sky.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀正确性状态专用 Redis 配置。
 * Sentinel 节点存在时由哨兵发现主节点；节点为空时保留单机回退，方便独立开发环境启动。
 */
@ConfigurationProperties(prefix = "sky.redis.seckill")
@Data
public class SeckillRedisProperties {

    // Sentinel 未配置时使用的单机地址。
    private String host = "localhost";
    private int port = 6380;
    // 主从 Redis 的统一访问密码。
    private String password;
    private int database = 0;
    // 秒杀 Redis 主节点发现配置。
    private Sentinel sentinel = new Sentinel();

    @Data
    public static class Sentinel {

        // sentinel monitor 中注册的主节点名称。
        private String master = "seckill-master";
        // 地址格式为 host:port；本地方案使用三个独立 Sentinel 进程。
        private List<String> nodes = new ArrayList<>();
    }
}
