package com.gdou.agent.api.controller;

import com.gdou.agent.api.common.Result;
import com.gdou.agent.api.constant.CodeConstant;
import com.gdou.agent.api.constant.MsgConstant;
import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.api.service.SessionService;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.infra.stream.SseStreamSink;
import com.gdou.agent.meta.entity.Session;
import com.gdou.agent.orchestration.GraphReturner;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/personal")
public class PersonalController {

    private static final long SSE_TIMEOUT = 120_000L;
    private static final String SAFE_ERROR = "本轮处理失败，请稍后重试";
    private static final String TIMEOUT_MESSAGE = "连接超时，请重试";

    @Autowired
    private GraphRunner runner;

    @Autowired
    @Qualifier("sseExecutor")
    private Executor sseExecutor;

    @Autowired
    private SessionService sessionService;

    /**
     * 用户新建会话窗口
     * @return
     */
    @GetMapping("/create")
    public Result create() {
        Session session = Session.builder()
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sessionService.save(session);
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, session.getId());
    }

    /**
     * 用户发起非流式响应对话
     * @param userMsg
     * @param sessionId
     * @return
     */
    @PostMapping("/chat/overAll")
    public Result chat(@RequestParam("userMsg") String userMsg,String sessionId) {
        Session session = sessionService.getById(sessionId);
        if (session.getTitle() == null) {
            session.setTitle(userMsg);
        }
        session.setUpdatedAt(LocalDateTime.now());
        sessionService.updateById(session);
        SessionHolder.set(session.getId());
        // traceId 由 ChatInterceptor 在请求线程写入，同一请求的节点日志共用它
        String traceId = MDC.get(GraphRunner.TRACE_ID);
        long start = System.currentTimeMillis();
        log.info("[chat/overAll] >>> 收到请求 traceId={}, userMsg={}", traceId, userMsg);
        try {
            RunContext ctx = new RunContext(traceId, new NoOpStreamSink());
            GraphReturner returner = runner.run(userMsg, ctx);
            log.info("[chat/overAll] <<< 完成 traceId={}, cost={}ms",
                    traceId, System.currentTimeMillis() - start);
            return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, returner);
        } catch (Exception e) {
            // 完整堆栈由 ExceptionHandle 统一记录，这里只留请求上下文，避免同一异常刷两遍
            log.error("[chat/overAll] 请求失败 traceId={}, cost={}ms, userMsg={}, type={}",
                    traceId, System.currentTimeMillis() - start, userMsg, e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 用户发起流式响应会话
     * @param userMsg
     * @return
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("userMsg") String userMsg,String sessionId) {
        Session session = sessionService.getById(sessionId);
        if (session.getTitle() == null) {
            session.setTitle(userMsg);
        }
        session.setUpdatedAt(LocalDateTime.now());
        sessionService.updateById(session);
        SessionHolder.set(session.getId());
        // MDC 是 ThreadLocal：traceId 是值，mdc 是整份上下文快照，后者供子线程日志复用
        String traceId = MDC.get(GraphRunner.TRACE_ID);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        long start = System.currentTimeMillis();
        log.info("[chat/stream] >>> 收到请求 traceId={}, userMsg={}", traceId, userMsg);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // sink 在请求线程创建，超时 / 异常回调才能拿到同一个实例统一收口
        SseStreamSink sink = new SseStreamSink(emitter, traceId);

        // 三个回调由容器线程执行，不继承请求线程的 MDC，需自行重建 traceId
        emitter.onTimeout(() -> withTrace(traceId, () -> {
            log.warn("[chat/stream] 连接超时 traceId={}, cost={}ms",
                    traceId, System.currentTimeMillis() - start);
            sink.fail(TIMEOUT_MESSAGE);
        }));
        emitter.onCompletion(() -> withTrace(traceId,
                () -> log.debug("[chat/stream] 连接结束 traceId={}", traceId)));
        emitter.onError(e -> withTrace(traceId, () -> {
            log.warn("[chat/stream] 连接异常 traceId={}, cause={}",
                    traceId, e.getClass().getSimpleName());
            sink.fail(SAFE_ERROR);
        }));

        try {
            sseExecutor.execute(() -> {
                if (mdc != null) MDC.setContextMap(mdc);

                try {
                    SessionHolder.set(session.getId());
                    RunContext ctx = new RunContext(traceId, sink);
                    GraphReturner returner = runner.run(userMsg, ctx);
                    sink.done(returner.getAnswer(), returner.getQuestionToUser());
                    log.info("[chat/stream] <<< 完成 traceId={}, cost={}ms",
                            traceId, System.currentTimeMillis() - start);
                } catch (Exception e) {
                    // 完整堆栈已由 [graph] 执行异常记录，这里只留一条收口摘要，避免重复刷屏
                    log.warn("[chat/stream] <<< 失败 traceId={}, cost={}ms, type={}",
                            traceId, System.currentTimeMillis() - start, e.getClass().getSimpleName());
                    sink.fail(SAFE_ERROR);
                } finally {
                    // 线程池线程会复用，不清会污染下一个请求的 traceId
                    MDC.clear();
                    SessionHolder.remove();
                }
            });
            return emitter;
        } catch (Exception e) {
            log.error("[chat/stream] 提交异步任务失败 traceId={}, userMsg={}, type={}",
                    traceId, userMsg, e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 容器回调线程重建 MDC，让日志前缀 [%X{traceId}] 与 [graph] 链路一致。
     * 必须恢复旧值而不是直接 remove：onCompletion 可能在 sse 线程内同步触发，
     * 直接 remove 会把该线程原有的 traceId 清掉，之后的完成日志就变成 [no-trace]。
     */
    private void withTrace(String traceId, Runnable action) {
        String previous = MDC.get(GraphRunner.TRACE_ID);
        if (traceId != null) MDC.put(GraphRunner.TRACE_ID, traceId);
        try {
            action.run();
        } finally {
            if (previous == null) MDC.remove(GraphRunner.TRACE_ID);
            else MDC.put(GraphRunner.TRACE_ID, previous);
        }
    }
}
