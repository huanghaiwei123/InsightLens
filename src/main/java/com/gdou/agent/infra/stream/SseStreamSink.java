package com.gdou.agent.infra.stream;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.StreamSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把状态机事件适配成 SSE 帧。每请求一个实例，由 Controller 创建并持有，
 * 不可注册成 Bean：emitter 与连接状态都是「这次连接」才有的。
 *
 * 四个接口方法只推事件，谁都不负责结束；done / fail 由 Controller 收口调用。
 * 超时回调与后台线程可能并发收口，因此两个职责必须拆开：
 * pushable 只管「还能不能推帧」，completed 用 CAS 保证 complete() 恰好调用一次。
 * 推送失败只停推送、不堵住收口，否则 emitter.complete() 永远不会被调用。
 */
@Slf4j
public class SseStreamSink implements StreamSink {

    private final SseEmitter emitter;
    private final String traceId;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile boolean pushable = true;

    public SseStreamSink(SseEmitter emitter, String traceId) {
        this.emitter = emitter;
        this.traceId = traceId;
    }

    @Override
    public void nodeStart(String node) {
        send("node-start", payload("node", node));
    }

    @Override
    public void nodeEnd(String node, long ms, AnalysisState state) {
        send("node-end", payload("node", node, "ms", ms, "retryCount", state.getRetryCount()));
    }

    @Override
    public void token(String text) {
        send("token", payload("text", text));
    }

    @Override
    public void error(String message) {
        send("error", payload("message", message));
    }

    /** 正常收口：Controller 拿到 GraphReturner 后调用一次 */
    public void done(String answer, String questionToUser) {
        if (questionToUser != null && !questionToUser.isBlank()) {
            send("question", payload("text", questionToUser));
        } else if (answer != null) {
            send("answer", payload("text", answer));
        }
        send("done", payload());
        log.debug("[sse] 正常收口 traceId={}, hasAnswer={}", traceId, answer != null);
        close();
    }

    /** 异常 / 超时收口：只推安全文案，不回显 e.getMessage() */
    public void fail(String safeMessage) {
        log.warn("[sse] 异常收口 traceId={}, reason={}", traceId, safeMessage);
        error(safeMessage);
        send("done", payload());
        close();
    }

    private void send(String type, Map<String, Object> data) {
        if (!pushable) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("traceId", traceId);
        event.putAll(data);
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("[sse] 推送中断 type={}, traceId={}, cause={}",
                    type, traceId, e.getClass().getSimpleName());
            pushable = false;
        }
    }

    private void close() {
        if (!completed.compareAndSet(false, true)) return;
        pushable = false;
        log.debug("[sse] 连接关闭 traceId={}", traceId);
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            // 容器可能已因超时 / 断连先行终止响应；收口标记已置位，不影响后续流程
            log.debug("[sse] 响应已由容器收口 traceId={}", traceId);
        }
    }

    /** 允许 null 值：Map.of 遇到 null 会直接 NPE */
    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            data.put((String) kv[i], kv[i + 1]);
        }
        return data;
    }
}
