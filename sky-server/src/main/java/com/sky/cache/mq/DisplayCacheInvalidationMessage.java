package com.sky.cache.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨应用实例广播的L1缓存失效消息；重复消费仍然安全。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DisplayCacheInvalidationMessage {

    private List<String> keys;
}
