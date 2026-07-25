package com.sky.config;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重建缓存线程配置
 */
@Configuration
@Slf4j
public class CacheExecutorConfiguration {
    private static final AtomicInteger THREAD_INDEX = new AtomicInteger(1);

    @Bean(
            name = "cacheRebuildExecutor",
            destroyMethod = "shutdown"
    )
    public ThreadPoolExecutor cacheRebuildExecutor() {

        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "cache-rebuild-"
                            + THREAD_INDEX.getAndIncrement()
            );

            // 让 Spring 关闭线程池后再结束线程
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler(
                    (currentThread, exception) ->
                            log.error("缓存重建线程异常，thread={}",currentThread.getName(),exception)
            );
            return thread;
        };

        return new ThreadPoolExecutor(4,8,60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),threadFactory,new ThreadPoolExecutor.AbortPolicy());
    }

}
