package com.gdou.agent.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * SSE 专用线程池：状态机在后台线程跑完，Controller 才能立刻 return emitter。
 * 与 Tomcat 请求线程池隔离，避免长连接占满 Web 线程；线程名前缀 sse- 便于日志排查。
 * 注意：子线程不继承 MDC，调用方须自行 getCopyOfContextMap / setContextMap / clear。
 */
@Configuration
public class SseExecutorConfig {

    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
