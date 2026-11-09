package com.gdou.test.api.interceptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gdou.agent.api.exception.ExceptionHandle;
import com.gdou.agent.api.interceptor.ChatInterceptor;
import com.gdou.agent.orchestration.GraphRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 链路追踪的生命周期：拦截器生成 traceId，异常兜底日志必须还能读到同一个 ID。
 * 用 standalone MockMvc 复现「Controller finally 提前清理 MDC」这个历史缺陷的回归测试，
 * 不启动 Spring Boot、不连 DuckDB。
 */
class ChatInterceptorTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/probe/fail")
        String fail() {
            throw new IllegalStateException("boom-for-trace-test");
        }
    }

    private ListAppender<ILoggingEvent> appender;
    private Logger interceptorLogger;
    private Logger exceptionLogger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MDC.clear();
        appender = new ListAppender<>();
        appender.start();
        interceptorLogger = (Logger) LoggerFactory.getLogger(ChatInterceptor.class);
        exceptionLogger = (Logger) LoggerFactory.getLogger(ExceptionHandle.class);
        interceptorLogger.addAppender(appender);
        exceptionLogger.addAppender(appender);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addInterceptors(new ChatInterceptor())
                .setControllerAdvice(new ExceptionHandle())
                .build();
    }

    @AfterEach
    void tearDown() {
        interceptorLogger.detachAppender(appender);
        exceptionLogger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    private String traceIdOf(String loggerName) {
        return appender.list.stream()
                .filter(e -> e.getLoggerName().equals(loggerName))
                .map(e -> e.getMDCPropertyMap().get(GraphRunner.TRACE_ID))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("异常路径：拦截器与 ExceptionHandle 的日志携带同一个 traceId")
    void exceptionLogKeepsSameTraceId() throws Exception {
        mvc.perform(get("/probe/fail"));

        String fromInterceptor = traceIdOf(ChatInterceptor.class.getName());
        String fromException = traceIdOf(ExceptionHandle.class.getName());
        assertThat(fromInterceptor).isNotBlank().hasSize(8);
        assertThat(fromException).isEqualTo(fromInterceptor);
    }

    @Test
    @DisplayName("请求结束后 MDC 已清理，且下一个请求拿到不同的 traceId")
    void cleansUpAndDoesNotLeakAcrossRequests() throws Exception {
        mvc.perform(get("/probe/fail"));
        String first = traceIdOf(ChatInterceptor.class.getName());
        assertThat(MDC.get(GraphRunner.TRACE_ID)).isNull();

        appender.list.clear();
        mvc.perform(get("/probe/ok"));
        String second = traceIdOf(ChatInterceptor.class.getName());

        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(MDC.get(GraphRunner.TRACE_ID)).isNull();
        assertThat(traceIdOf(ExceptionHandle.class.getName())).isNull();
    }
}
