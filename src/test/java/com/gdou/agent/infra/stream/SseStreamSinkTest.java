package com.gdou.agent.infra.stream;

import com.gdou.agent.orchestration.AnalysisState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SSE 收口的回归用例：用 mock 的 SseEmitter 制造推送失败与重复收口，
 * 不启动容器、不建连接，只验证「停推送」与「关连接」两条职责互不干扰。
 */
class SseStreamSinkTest {

    private static final String SAFE_ERROR = "本轮处理失败，请稍后重试";

    @Test
    @DisplayName("推送失败只停推送，收口仍会 complete 一次")
    void sendFailureStillCompletes() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("connection closed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        SseStreamSink sink = new SseStreamSink(emitter, "trace01");

        sink.fail(SAFE_ERROR);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("done 与 fail 重复收口，complete 恰好一次且不再推帧")
    void completeOnlyOnce() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter, "trace02");

        sink.done("上周退款率 3.2%", null);
        sink.fail(SAFE_ERROR);

        // answer + done 两帧；第二次收口被 completed 挡住
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("推送中断后节点继续埋点不会把异常抛回状态机")
    void noExceptionAfterPushFailure() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        SseStreamSink sink = new SseStreamSink(emitter, "trace03");

        sink.nodeStart("sql_generate");

        assertThatCode(() -> {
            sink.nodeEnd("sql_generate", 10L, new AnalysisState());
            sink.token("片段");
            sink.error(SAFE_ERROR);
        }).doesNotThrowAnyException();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
