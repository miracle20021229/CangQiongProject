package com.sky.cache;

/**
 * 展示缓存Key统一入口；v1用于后续数据结构升级时整体切换命名空间。
 */
public final class DisplayCacheKeys {

    private static final String DISH_LIST_PREFIX = "cache:dish:list:v1:";
    private static final String SETMEAL_LIST_PREFIX = "cache:setmeal:list:v1:";

    private DisplayCacheKeys() {}

    public static String dishList(Long categoryId) {
        return DISH_LIST_PREFIX + categoryId;
    }

    public static String setmealList(Long categoryId) {
        return SETMEAL_LIST_PREFIX + categoryId;
    }
}
