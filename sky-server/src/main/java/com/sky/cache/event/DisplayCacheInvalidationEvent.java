package com.sky.cache.event;

import java.util.Set;

/**
 * MySQL事务内发布、事务提交后执行的展示缓存精确失效事件。
 */
public record DisplayCacheInvalidationEvent(Set<String> keys) {

    /**
     * keys表示那些本次实例中失效或者修改删除的key
     */
    public DisplayCacheInvalidationEvent {
        keys = Set.copyOf(keys);
    }
}
