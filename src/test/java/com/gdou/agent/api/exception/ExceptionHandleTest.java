package com.gdou.agent.api.exception;

import com.gdou.agent.infra.constant.CodeConstant;
import com.gdou.agent.infra.constant.MsgConstant;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常兜底：前端必须拿到与成功响应同构的 Result，且不能泄漏模型返回原文。
 * 部分 BusinessException 的 message 拼了 raw（提示词 + SQL），这是这个测试最关键的断言。
 */
class ExceptionHandleTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/business")
        String business() {
            throw new BusinessException("原文：SENSITIVE_RAW_SQL_WITH_PROMPT");
        }

        @GetMapping("/probe/business-with-code")
        String businessWithCode() {
            throw new BusinessException("400", "自定义业务错误码");
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("unexpected boom");
        }

        @GetMapping("/probe/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/favicon.ico");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ExceptionHandle())
            .build();

    @Test
    @DisplayName("业务异常：code 回落 500，对外统一文案，不回显带原文的 message")
    void businessExceptionReturnsUniformResult() throws Exception {
        mvc.perform(get("/probe/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CodeConstant.CALL_ERROR))
                .andExpect(jsonPath("$.msg").value(MsgConstant.CALL_ERROR))
                .andExpect(content().string(not(containsString("SENSITIVE_RAW_SQL_WITH_PROMPT"))));
    }

    @Test
    @DisplayName("显式业务码的异常：透传 code 并把业务消息回显到 data")
    void keepCustomBusinessCode() throws Exception {
        mvc.perform(get("/probe/business-with-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.msg").value(MsgConstant.CALL_ERROR))
                .andExpect(jsonPath("$.data").value("自定义业务错误码"));
    }

    @Test
    @DisplayName("未预期异常同样收敛成 Result，不抛到 Spring 默认错误体")
    void unexpectedExceptionReturnsUniformResult() throws Exception {
        mvc.perform(get("/probe/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CodeConstant.CALL_ERROR))
                .andExpect(content().string(not(containsString("unexpected boom"))));
    }

    @Test
    @DisplayName("静态资源缺失：返回 404，不再走兜底生成 Result 错误体")
    void noResourceFoundDoesNotUseFallback() throws Exception {
        mvc.perform(get("/probe/no-resource"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("直接调用 handler：code 为 null 时回落到通用错误码")
    void handlerCodeFallback() {
        ExceptionHandle handler = new ExceptionHandle();
        assertThat(handler.handleBusinessException(new BusinessException("无 code")).code)
                .isEqualTo(CodeConstant.CALL_ERROR);
        assertThat(handler.handleBusinessException(new BusinessException("400", "有 code")).code)
                .isEqualTo("400");
    }
}
