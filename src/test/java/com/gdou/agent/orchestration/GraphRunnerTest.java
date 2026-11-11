package com.gdou.agent.orchestration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机调度：节点顺序、澄清中断、两条修正回边、retryCount 语义、MDC 生命周期、节点埋点。
 * 用假的 Node 填充 Map，不启动 Spring、不调模型、不连 DuckDB。
 * GraphRunner 的入口是 run(question, RunContext)，sink 由调用方显式注入。
 */
class GraphRunnerTest {

    private static final List<String> ALL_NODES = List.of(
            NodeName.CLARIFY, NodeName.SCHEMA_RETRIEVE, NodeName.SQL_GENERATE,
            NodeName.SQL_GUARD, NodeName.SQL_EXECUTE, NodeName.JUDGE, NodeName.SUMMARIZE);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 普通同步调用：事件全部丢弃，等价于线上的 /chat/overAll */
    private static RunContext testContext() {
        return new RunContext("unit-test", new NoOpStreamSink());
    }

    /** 流式或埋点断言：换成记录型 sink */
    private static RunContext testContext(StreamSink sink) {
        return new RunContext("unit-test", sink);
    }

    /** 收集节点事件，验证 nodeStart / nodeEnd 报的是本节点名 */
    private static class RecordingStreamSink implements StreamSink {
        private final List<String> starts = new ArrayList<>();
        private final List<String> ends = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void nodeStart(String node) { starts.add(node); }

        @Override
        public void nodeEnd(String node, long ms, AnalysisState state) { ends.add(node); }

        @Override
        public void token(String text) { }

        @Override
        public void error(String message) { errors.add(message); }
    }

    /** 构造七个假节点，记录调用顺序，按 behaviors 改写 state */
    private static Map<String, Node> nodes(List<String> order, Map<String, Consumer<AnalysisState>> behaviors) {
        Map<String, Node> map = new HashMap<>();
        for (String name : ALL_NODES) {
            Consumer<AnalysisState> behavior = behaviors.getOrDefault(name, s -> {});
            map.put(name, (state, ctx) -> {
                order.add(name);
                behavior.accept(state);
            });
        }
        return map;
    }

    /** 一条能从头走到尾的正常链路 */
    private static Map<String, Consumer<AnalysisState>> happyPath() {
        Map<String, Consumer<AnalysisState>> b = new HashMap<>();
        b.put(NodeName.CLARIFY, s -> s.setNeedClarify(false));
        b.put(NodeName.SQL_GENERATE, s -> s.setSql("select 1 from sales limit 10"));
        b.put(NodeName.SQL_GUARD, s -> {
            s.setValidationResult("pass");
            s.setError(null);
        });
        b.put(NodeName.SQL_EXECUTE, s -> s.setRowCount(1));
        b.put(NodeName.JUDGE, s -> {
            s.setVerdict("ok");
            s.setError(null);
            s.setAnswer("上周退款率 3.2%");
        });
        return b;
    }

    @Test
    @DisplayName("正常链路按 ①→⑦ 顺序各走一次，返回答案")
    void fullHappyPath() {
        List<String> order = new ArrayList<>();
        GraphReturner result = new GraphRunner(nodes(order, happyPath())).run("上周退款率", testContext());

        assertThat(order).containsExactly(
                NodeName.CLARIFY, NodeName.SCHEMA_RETRIEVE, NodeName.SQL_GENERATE,
                NodeName.SQL_GUARD, NodeName.SQL_EXECUTE, NodeName.JUDGE, NodeName.SUMMARIZE);
        assertThat(result.getAnswer()).isEqualTo("上周退款率 3.2%");
        assertThat(result.getQuestionToUser()).isNull();
    }

    @Test
    @DisplayName("needClarify=true 时只走 clarify，返回追问而不继续生成 SQL")
    void stopAtClarify() {
        List<String> order = new ArrayList<>();
        Map<String, Consumer<AnalysisState>> b = new HashMap<>();
        b.put(NodeName.CLARIFY, s -> {
            s.setNeedClarify(true);
            s.setQuestionToUser("「这次」指哪次考试？");
        });
        GraphReturner result = new GraphRunner(nodes(order, b)).run("这次平均分怎么样", testContext());

        assertThat(order).containsExactly(NodeName.CLARIFY);
        assertThat(result.getQuestionToUser()).isEqualTo("「这次」指哪次考试？");
        assertThat(result.getAnswer()).isNull();
    }

    @Test
    @DisplayName("guard reject 回退到 sql_generate，retryCount +1，第二轮通过后走完")
    void guardRejectTriggersOneRepairRound() {
        List<String> order = new ArrayList<>();
        List<Integer> retrySeen = new ArrayList<>();
        Map<String, Consumer<AnalysisState>> b = happyPath();
        b.put(NodeName.SQL_GENERATE, s -> {
            retrySeen.add(s.getRetryCount());
            s.setSql("select 1 from sales limit 10");
        });
        AtomicInteger guardCalls = new AtomicInteger();
        b.put(NodeName.SQL_GUARD, s -> {
            if (guardCalls.getAndIncrement() == 0) {
                s.setValidationResult("reject");
                s.setError("查询了白名单之外的表：secret_table");
            } else {
                s.setValidationResult("pass");
                s.setError(null); // 真实节点会清，否则 nextSqlJudge 会再次打回
            }
        });
        GraphRunner runner = new GraphRunner(nodes(order, b));

        GraphReturner result = runner.run("查一下销量", testContext());

        assertThat(result.getAnswer()).isEqualTo("上周退款率 3.2%");
        assertThat(order).containsExactly(
                NodeName.CLARIFY, NodeName.SCHEMA_RETRIEVE, NodeName.SQL_GENERATE, NodeName.SQL_GUARD,
                NodeName.SQL_GENERATE, NodeName.SQL_GUARD, NodeName.SQL_EXECUTE,
                NodeName.JUDGE, NodeName.SUMMARIZE);
        // retryCount 是「已发生的回退次数」：首次生成看到 0，第一轮修正看到 1
        assertThat(retrySeen).containsExactly(0, 1);
    }

    @Test
    @DisplayName("judge 判 off_target 回退到 sql_generate，retryCount +1，第二轮判 ok")
    void judgeOffTargetTriggersOneRepairRound() {
        List<String> order = new ArrayList<>();
        List<Integer> retrySeen = new ArrayList<>();
        Map<String, Consumer<AnalysisState>> b = happyPath();
        b.put(NodeName.SQL_GENERATE, s -> {
            retrySeen.add(s.getRetryCount());
            s.setSql("select 1 from sales limit 10");
        });
        AtomicInteger judgeCalls = new AtomicInteger();
        b.put(NodeName.JUDGE, s -> {
            if (judgeCalls.getAndIncrement() == 0) {
                s.setVerdict("off_target");
                s.setError("按品类退款率应 GROUP BY category，请重写");
                s.setAnswer(null);
            } else {
                s.setVerdict("ok");
                s.setError(null);
                s.setAnswer("上周退款率 3.2%");
            }
        });

        GraphReturner result = new GraphRunner(nodes(order, b)).run("上周各品类退款率", testContext());

        assertThat(result.getAnswer()).isEqualTo("上周退款率 3.2%");
        assertThat(retrySeen).containsExactly(0, 1);
        assertThat(java.util.Collections.frequency(order, NodeName.SQL_GENERATE)).isEqualTo(2);
        assertThat(java.util.Collections.frequency(order, NodeName.JUDGE)).isEqualTo(2);
    }

    @Test
    @DisplayName("节点埋点按真实节点名成对推送，顺序与执行顺序一致")
    void sinkReceivesPairedNodeEvents() {
        List<String> order = new ArrayList<>();
        RecordingStreamSink sink = new RecordingStreamSink();

        new GraphRunner(nodes(order, happyPath())).run("上周退款率", testContext(sink));

        assertThat(order).hasSize(7);
        assertThat(sink.starts).isEqualTo(order);
        assertThat(sink.ends).isEqualTo(order);
        assertThat(sink.errors).isEmpty();
    }

    @Test
    @DisplayName("guard 回退时埋点重复记录 sql_generate，nodeEnd 不报下一节点名")
    void sinkEventsCoverRepairRound() {
        List<String> order = new ArrayList<>();
        Map<String, Consumer<AnalysisState>> b = happyPath();
        AtomicInteger guardCalls = new AtomicInteger();
        b.put(NodeName.SQL_GUARD, s -> {
            if (guardCalls.getAndIncrement() == 0) {
                s.setValidationResult("reject");
                s.setError("查询了白名单之外的表：secret_table");
            } else {
                s.setValidationResult("pass");
                s.setError(null);
            }
        });
        RecordingStreamSink sink = new RecordingStreamSink();

        new GraphRunner(nodes(order, b)).run("查一下销量", testContext(sink));

        assertThat(java.util.Collections.frequency(sink.starts, NodeName.SQL_GENERATE)).isEqualTo(2);
        assertThat(sink.starts).isEqualTo(order);
        assertThat(sink.ends).isEqualTo(order);
        assertThat(sink.errors).isEmpty();
    }

    @Test
    @DisplayName("MDC 无 traceId 时 run 自生成并写入日志，结束后自行清理")
    void generatesTraceWhenAbsent() {
        MDC.clear();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger graphLogger = (Logger) LoggerFactory.getLogger(GraphRunner.class);
        graphLogger.addAppender(appender);
        try {
            new GraphRunner(nodes(new ArrayList<>(), happyPath())).run("问题", testContext());
            assertThat(appender.list).isNotEmpty();
            String traceId = appender.list.get(0).getMDCPropertyMap().get(GraphRunner.TRACE_ID);
            assertThat(traceId).isNotBlank().hasSize(8);
            assertThat(appender.list)
                    .allSatisfy(e -> assertThat(e.getMDCPropertyMap().get(GraphRunner.TRACE_ID)).isEqualTo(traceId));
            assertThat(MDC.get(GraphRunner.TRACE_ID)).isNull();
        } finally {
            graphLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("HTTP 入口已有 traceId 时复用且不清理（清理归拦截器）")
    void reusesPresetTrace() {
        MDC.put(GraphRunner.TRACE_ID, "preset01");
        new GraphRunner(nodes(new ArrayList<>(), happyPath())).run("问题", testContext());
        assertThat(MDC.get(GraphRunner.TRACE_ID)).isEqualTo("preset01");
    }

    @Test
    @DisplayName("节点抛异常时自生成的 traceId 仍会被清理")
    void cleansTraceOnException() {
        MDC.clear();
        Map<String, Consumer<AnalysisState>> b = happyPath();
        b.put(NodeName.SCHEMA_RETRIEVE, s -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> new GraphRunner(nodes(new ArrayList<>(), b)).run("问题", testContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(MDC.get(GraphRunner.TRACE_ID)).isNull();
    }
}
