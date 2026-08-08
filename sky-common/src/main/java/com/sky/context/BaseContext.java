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
     *
     * <p><b>为什么需要这个fail-fast方法？</b>
     * 依赖用户ID的<u>写操作</u>（如领券时往user_coupon插入记录）必须保证userId非空：
     * 若直接拿getCurrentId()的null去落库，MySQL唯一索引对NULL不生效（NULL != NULL），
     * uk_coupon_user的一人一券兜底会被绕过，产生user_id为null的脏数据。
     * 读操作（如按用户查列表）查不到数据只会返回空结果，无此风险，可继续裸用getCurrentId()。
     *
     * <p><b>什么情况下会为null？</b>
     * 正常HTTP链路中不会为null：/user/**与/admin/**请求都会先被JWT拦截器校验，
     * token解析失败直接返回401、请求到不了Service层；解析成功才写入用户ID。
     * 但以下场景仍可能拿到null，调用方不能想当然：
     * <ol>
     *   <li>请求绕过拦截器：未注册拦截的白名单路径（如登录接口本身）、
     *       未来新增路径忘记注册拦截器、或代码内部直接调用Service方法；</li>
     *   <li>非HTTP上下文直接调用Service：单元测试、MQ消费者、定时任务，
     *       这些场景没有JWT拦截器写入用户ID；</li>
     *   <li>跨线程调用：ThreadLocal是线程绑定的，不会随子线程传递，
     *       在异步线程池（如缓存重建线程池cacheRebuildExecutor）中读不到主线程的ID；</li>
     *   <li>token已过期/解析异常但被错误放行，或token载荷中缺少USER_ID声明。</li>
     * </ol>
     *
     * @return 当前登录用户ID
     * @throws UserNotLoginException 用户未登录（线程上下文中无用户ID）
     */
    public static Long getCurrentIdOrThrow() {
        Long id = threadLocal.get();
        if (id == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return id;
    }

}
