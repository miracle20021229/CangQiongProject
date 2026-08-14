package com.sky.context;

import com.sky.constant.MessageConstant;
import com.sky.exception.UserNotLoginException;

/**
 * 线程级用户上下文，存放当前请求登录用户的ID。
 *
 * <p>写入与清理都由JWT拦截器负责，Service层只读不写：
 * 用户端JwtTokenUserInterceptor在请求进入Controller前把userId写入ThreadLocal，
 * 管理端JwtTokenAdminInterceptor同理；请求处理结束后调用removeCurrentId()清理，
 * 避免容器线程池复用线程时把上一个请求的用户ID泄漏给下一个请求（串号）。
 */
public class BaseContext {

    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static Long getCurrentId() {
        return threadLocal.get();
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }

    /**
     * 获取当前登录用户ID，线程上下文中不存在用户ID时抛出业务异常（fail-fast）。
     */
    public static Long getCurrentIdOrThrow() {
        Long id = threadLocal.get();
        if (id == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return id;
    }

}
