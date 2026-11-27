package com.gdou.agent.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.service.TraceSpanService;
import com.gdou.agent.meta.entity.TraceSpan;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 七节点内核的可观测收集器。
 *
 * <p>职责边界：只负责「攒 span + 请求结束时一次批量落库」，不参与状态机决策。
 * 设计约定见 data-agent/design/trace-convention.md，关键三条：</p>
 * <ol>
 *   <li>一次请求攒成 List，结束时一条语句批量写。逐条 INSERT 会把观测开销算进 P95。</li>
 *   <li>跳过/未走到/异常中断的节点也要记行，行数恒 ≥ 8（1 根 + 7 节点 + N 个 llm.call）。</li>
 *   <li>trace 是可丢数据：写失败只记 warn，绝不向上抛异常、绝不阻塞主链路。</li>
 * </ol>
 *
 * <p>用 ThreadLocal 持有当前请求的草稿，而不是往 RunContext 里加参数——
 * llm.call 埋点在 LlmClient 实现里，那里拿不到 RunContext。
 * ThreadLocal 由 begin()/flush() 成对管理，flush() 里必定 remove()。</p>
 */
@Slf4j
@Component
public class TraceRecorder {

    public static final String SPAN_REQUEST = "agent.request";
    public static final String SPAN_LLM = "llm.call";

    /** 七个节点标识符，顺序与设计文档一致。未在本次请求中走到的节点按 skip 补行。 */
    private static final List<String> NODE_NAMES = List.of(
            NodeName.CLARIFY, NodeName.SCHEMA_RETRIEVE, NodeName.SQL_GENERATE,
            NodeName.SQL_GUARD, NodeName.SQL_EXECUTE, NodeName.JUDGE, NodeName.SUMMARIZE);

    private final TraceSpanService traceSpanService;
    private final ObjectMapper objectMapper;
    private final String modelName;

    private final ThreadLocal<RequestTrace> current = new ThreadLocal<>();

    public TraceRecorder(TraceSpanService traceSpanService,
                         ObjectMapper objectMapper,
                         @Value("${spring.ai.openai.model:unknown}") String modelName) {
        this.traceSpanService = traceSpanService;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    /** 一次请求开始：建立草稿容器，并预置 7 个 skip 节点兜底行。 */
    public void begin(String traceId, String question, Integer sessionId) {
        RequestTrace trace = new RequestTrace(traceId, question, sessionId);
        LocalDateTime now = LocalDateTime.now();
        for (String node : NODE_NAMES) {
            trace.drafts.add(new Draft(node, now, now,
                    Map.of("skip", true, "reason", skipReason(node))));
        }
        current.set(trace);
    }

    /** 节点开始执行：后续 llm.call 的 parent 取这个值，按节点标识符而非时间窗归因。 */
    public void currentNode(String node) {
        RequestTrace trace = current.get();
        if (trace != null) trace.currentNode = node;
    }

    /**
     * 节点执行完成：替换该节点第一条尚未落地的 skip 兜底行；
     * 修正循环导致的重复执行则追加新行（③ 重写两次就有两个 node.sql_generate）。
     */
    public void nodeDone(String node, LocalDateTime start, long latencyMs, Map<String, Object> attrs) {
        RequestTrace trace = current.get();
        if (trace == null) return;
        LocalDateTime end = start.plusNanos(Math.max(0, latencyMs) * 1_000_000L);
        Map<String, Object> payload = new LinkedHashMap<>(attrs);
        payload.put("latency_ms", latencyMs);
        for (int i = 0; i < trace.drafts.size(); i++) {
            Draft d = trace.drafts.get(i);
            if (d.node.equals(node) && Boolean.TRUE.equals(d.attrs.get("skip"))) {
                trace.drafts.set(i, new Draft(node, start, end, payload));
                return;
            }
        }
        trace.drafts.add(new Draft(node, start, end, payload));
    }

    /** 模型调用明细，挂在调用它的节点下；parent 写节点标识符，不靠时间窗对齐。 */
    public void llmCall(Integer tokensIn, Integer tokensOut, long latencyMs) {
        RequestTrace trace = current.get();
        if (trace == null) return;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("parent", trace.currentNode);
        attrs.put("model", modelName);
        attrs.put("tokens_in", tokensIn);
        attrs.put("tokens_out", tokensOut);
        attrs.put("latency_ms", latencyMs);
        trace.drafts.add(new Draft(SPAN_LLM, LocalDateTime.now().minusNanos(Math.max(0, latencyMs) * 1_000_000L),
                LocalDateTime.now(), attrs));
    }

    /**
     * 请求结束：补根 span，一次批量写库并清理 ThreadLocal。
     * success 由调用方按是否拿到答案判定；异常路径也要调用，保证 end_at 不为 NULL。
     */
    public void flush(String question, boolean success, String outcome) {
        RequestTrace trace = current.get();
        if (trace == null) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> rootAttrs = new LinkedHashMap<>();
            rootAttrs.put("question", question);
            rootAttrs.put("session_id", trace.sessionId);
            rootAttrs.put("latency_ms", java.time.Duration.between(trace.startedAt, now).toMillis());
            rootAttrs.put("success", success);
            rootAttrs.put("outcome", outcome);

            List<TraceSpan> spans = new ArrayList<>(trace.drafts.size() + 1);
            spans.add(toSpan(trace.traceId, SPAN_REQUEST, trace.startedAt, now, rootAttrs));
            for (Draft d : trace.drafts) {
                spans.add(toSpan(trace.traceId, d.node, d.start, d.end, d.attrs));
            }
            // saveBatch 走一个事务的 JDBC batch，不是逐条自动提交
            traceSpanService.saveBatch(spans);
            log.debug("[trace] 落库 traceId={}, spans={}, success={}", trace.traceId, spans.size(), success);
        } catch (Exception e) {
            // trace 可丢：写失败绝不影响已经产出的答案
            log.warn("[trace] 落库失败 traceId={}, spans={}, cause={}",
                    trace.traceId, trace.drafts.size() + 1, e.getClass().getSimpleName());
        } finally {
            current.remove();
        }
    }

    private TraceSpan toSpan(String traceId, String node, LocalDateTime start,
                             LocalDateTime end, Map<String, Object> attrs) {
        return TraceSpan.builder()
                .traceId(traceId)
                .node(node)
                .startAt(start)
                .endAt(end)
                .attrs(writeJson(attrs))
                .build();
    }

    private String writeJson(Map<String, Object> attrs) {
        try {
            return objectMapper.writeValueAsString(attrs);
        } catch (Exception e) {
            log.warn("[trace] attrs 序列化失败，降级为空对象：{}", e.getClass().getSimpleName());
            return "{}";
        }
    }

    /** reason 只允许枚举值，自由文本会让 GROUP BY 统计碎掉。 */
    private String skipReason(String node) {
        if (NodeName.CLARIFY.equals(node)) return "question_complete";
        if (NodeName.SCHEMA_RETRIEVE.equals(node)) return "single_table";
        if (NodeName.SUMMARIZE.equals(node)) return "raw_data_only";
        return "not_visited";
    }

    private record Draft(String node, LocalDateTime start, LocalDateTime end, Map<String, Object> attrs) {}

    private static final class RequestTrace {
        private final String traceId;
        private final String question;
        private final Integer sessionId;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private final List<Draft> drafts = new ArrayList<>();
        private String currentNode = "unknown";

        private RequestTrace(String traceId, String question, Integer sessionId) {
            this.traceId = traceId;
            this.question = question;
            this.sessionId = sessionId;
        }
    }
}
