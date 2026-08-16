package com.sky.seckill.governance.support;

/**
 * 流程6治理摘要的统一格式化与数据库字段长度保护。
 */
public final class ResolutionFormatter {

    private static final int MAX_MESSAGE_LENGTH = 1000;

    private ResolutionFormatter() {
    }

    /**
     * 将异常转换为只包含异常类型和首层消息的内部摘要。
     */
    public static String summarize(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        return truncate(throwable.getClass().getSimpleName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
    }

    /**
     * 截断超长治理摘要以匹配数据库字段上限。
     */
    public static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
