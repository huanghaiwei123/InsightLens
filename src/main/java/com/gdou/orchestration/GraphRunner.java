package com.gdou.orchestration;

import com.gdou.infra.stream.NoOpStreamSink;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 七节点状态机调度器：clarify → schema_retrieve → sql_generate → sql_guard
 * → sql_execute → judge → summarize，唯一回边是 ⑥/④ → ③ 的 SQL 修正循环。
 *
 * 链路追踪：run() 开始时把 traceId 写入 MDC（日志格式见 application.yaml），
 * 并构造 RunContext 传给每个节点。HTTP 入口若已生成 traceId 则复用，
 * 直接调用 run() 时自行生成；谁生成谁在 finally 里清理，避免污染线程池的下一个请求。
 *
 * 并发安全：本类由 Spring 单例管理，但当前节点名是 run() 的局部变量；
 * next 方法统一「接收当前节点名、返回下一节点名」，不读写任何共享可变状态，
 * 因此多个请求可以同时进入 run() 而互不干扰，也无需在入口重置字段。
 */
@Slf4j
@Component
public class GraphRunner {

    public static final String TRACE_ID = "traceId";

    private final Map<String, Node> node;

    public GraphRunner(Map<String, Node> node) {
        this.node = node;
    }

    public Object run(String question) {
        long start = System.currentTimeMillis();
        boolean ownsTrace = false;
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
            MDC.put(TRACE_ID, traceId);
            ownsTrace = true;
        }
        RunContext ctx = new RunContext(traceId, new NoOpStreamSink());

        String nodeName = NodeName.CLARIFY;
        AnalysisState state = new AnalysisState();
        state.setQuestion(question);
        log.info("[graph] >>> 开始 traceId={}, question={}", traceId, question);

        try {
            int step = 0;
            while (!nodeName.equals(NodeName.DONE)) {
                String current = nodeName;
                long nodeStart = System.currentTimeMillis();
                step++;
                log.info("[graph] step={} 进入节点 {}", step, current);
                switch (current) {
                    case NodeName.CLARIFY -> {
                        node.get(NodeName.CLARIFY).apply(state, ctx);
                        nodeName = nextClarify(state, current);
                        if (nodeName.equals(NodeName.DONE)) {
                            log.info("[graph] <<< 结束(澄清中断) traceId={}, cost={}ms, questionToUser={}",
                                    traceId, System.currentTimeMillis() - start, state.getQuestionToUser());
                            return state.getQuestionToUser();
                        }
                    }
                    case NodeName.SCHEMA_RETRIEVE -> {
                        node.get(NodeName.SCHEMA_RETRIEVE).apply(state, ctx);
                        nodeName = nextSchemaRetrieve(current);
                    }
                    case NodeName.SQL_GENERATE -> {
                        node.get(NodeName.SQL_GENERATE).apply(state, ctx);
                        nodeName = nextSqlGenerate(current);
                    }
                    case NodeName.SQL_GUARD -> {
                        node.get(NodeName.SQL_GUARD).apply(state, ctx);
                        nodeName = nextSqlGuard(state, current);
                    }
                    case NodeName.SQL_EXECUTE -> {
                        node.get(NodeName.SQL_EXECUTE).apply(state, ctx);
                        nodeName = nextSqlExecute(current);
                    }
                    case NodeName.JUDGE -> {
                        node.get(NodeName.JUDGE).apply(state, ctx);
                        nodeName = nextSqlJudge(state, current);
                    }
                    case NodeName.SUMMARIZE -> {
                        node.get(NodeName.SUMMARIZE).apply(state, ctx);
                        nodeName = nextSummarize(current);
                    }
                    default -> {
                        // 原实现遇到未知节点名会一直空转，这里收敛为结束并留一条 ERROR
                        log.error("[graph] 未知节点名 {}，终止本轮", current);
                        nodeName = NodeName.DONE;
                    }
                }
                log.info("[graph] step={} 节点 {} 完成 cost={}ms → 下一节点 {}",
                        step, current, System.currentTimeMillis() - nodeStart, nodeName);
            }
            log.info("[graph] <<< 结束 traceId={}, cost={}ms, step={}, retryCount={}, answerLen={}",
                    traceId, System.currentTimeMillis() - start, step, state.getRetryCount(),
                    state.getAnswer() == null ? 0 : state.getAnswer().length());
            return state;
        } catch (Exception e) {
            log.error("[graph] 执行异常 traceId={}, 中断节点={}, cost={}ms, question={}",
                    traceId, nodeName, System.currentTimeMillis() - start, question, e);
            throw e;
        } finally {
            if (ownsTrace) {
                MDC.remove(TRACE_ID);
            }
        }
    }

    private String nextClarify(AnalysisState state, String current) {
        String next = Boolean.TRUE.equals(state.getNeedClarify())
                ? NodeName.DONE : NodeName.SCHEMA_RETRIEVE;
        log.info("[graph] clarify 路由 from={} → {}, needClarify={}", current, next, state.getNeedClarify());
        return next;
    }

    private String nextSchemaRetrieve(String current) {
        log.debug("[graph] 路由 from={} → {}", current, NodeName.SQL_GENERATE);
        return NodeName.SQL_GENERATE;
    }

    private String nextSqlGenerate(String current) {
        log.debug("[graph] 路由 from={} → {}", current, NodeName.SQL_GUARD);
        return NodeName.SQL_GUARD;
    }

    private String nextSqlGuard(AnalysisState state, String current) {
        String next = state.getValidationResult().equals("pass")
                ? NodeName.SQL_EXECUTE : NodeName.SQL_GENERATE;
        if (next.equals(NodeName.SQL_GENERATE)) {
            state.setRetryCount(state.getRetryCount() + 1);
            log.warn("[graph] sql_guard 拒绝，回退 sql_generate 修正：from={} → {}, reason={}, retryCount={}",
                    current, next, state.getError(), state.getRetryCount());
        }
        return next;
    }

    private String nextSqlExecute(String current) {
        log.debug("[graph] 路由 from={} → {}", current, NodeName.JUDGE);
        return NodeName.JUDGE;
    }

    private String nextSqlJudge(AnalysisState state, String current) {
        String verdict = state.getVerdict();
        String next = state.getError() == null ? NodeName.SUMMARIZE : NodeName.SQL_GENERATE;
        state.setVerdict(null); // 重置字段为 null，防止 JudgeNode 第二轮被持续污染
        if (next.equals(NodeName.SQL_GENERATE)) {
            state.setRetryCount(state.getRetryCount() + 1);
            log.warn("[graph] judge 判定 {}，回退 sql_generate 修正：from={} → {}, reason={}, retryCount={}",
                    verdict, current, next, state.getError(), state.getRetryCount());
        } else {
            log.info("[graph] judge 判定 {}，from={} → 进入 summarize", verdict, current);
        }
        return next;
    }

    private String nextSummarize(String current) {
        log.debug("[graph] 路由 from={} → {}", current, NodeName.DONE);
        return NodeName.DONE;
    }
}
