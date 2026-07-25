package com.sky.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期封装对象
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
