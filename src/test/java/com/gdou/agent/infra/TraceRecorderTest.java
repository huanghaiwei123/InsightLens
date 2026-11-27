package com.gdou.agent.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.llm.TraceRecorder;
import com.gdou.agent.service.TraceSpanService;
import com.gdou.agent.meta.entity.TraceSpan;
import com.gdou.agent.orchestration.node.NodeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Trace 收集器：验证攒批落库、跳过节点补行、修正循环追加行、写失败不影响主链路。
 * 纯 mock，不连 DuckDB。
 */
class TraceRecorderTest {

    private TraceSpanService service;
    private TraceRecorder recorder;

    @BeforeEach
    void setUp() {
        service = mock(TraceSpanService.class);
        recorder = new TraceRecorder(service, new ObjectMapper(), "test-model");
    }

    @SuppressWarnings("unchecked")
    private List<TraceSpan> capturedSpans() {
        ArgumentCaptor<List<TraceSpan>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).saveBatch(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("一次请求恒写 8 行：1 根 + 7 节点，未走到的节点也补 skip 行")
    void flushWritesRootAndSevenNodeSpans() {
        recorder.begin("t1", "退款率是多少", 7);
        recorder.currentNode(NodeName.CLARIFY);
        recorder.nodeDone(NodeName.CLARIFY, LocalDateTime.now(), 12L,
                Map.of("need_clarify", false));
        recorder.flush("退款率是多少", true, "answer");

        List<TraceSpan> spans = capturedSpans();
        assertThat(spans).hasSize(8);
        assertThat(spans.get(0).getNode()).isEqualTo(TraceRecorder.SPAN_REQUEST);
        assertThat(spans.stream().map(TraceSpan::getNode))
                .containsExactlyInAnyOrder(
                        TraceRecorder.SPAN_REQUEST,
                        NodeName.CLARIFY, NodeName.SCHEMA_RETRIEVE, NodeName.SQL_GENERATE,
                        NodeName.SQL_GUARD, NodeName.SQL_EXECUTE, NodeName.JUDGE, NodeName.SUMMARIZE);
        assertThat(spans.get(0).getEndAt()).isNotNull();
    }

    @Test
    @DisplayName("llm.call 的 parent 取当前节点，且 reason 只写枚举值")
    void llmCallParentAndSkipReasonAreEnum() {
        recorder.begin("t2", "q", null);
        recorder.currentNode(NodeName.SQL_GENERATE);
        recorder.llmCall(100, 20, 350L);
        recorder.nodeDone(NodeName.SQL_GENERATE, LocalDateTime.now(), 400L,
                Map.of("sql", "select 1"));
        recorder.flush("q", true, "answer");

        List<TraceSpan> spans = capturedSpans();
        TraceSpan llm = spans.stream()
                .filter(s -> TraceRecorder.SPAN_LLM.equals(s.getNode()))
                .findFirst().orElseThrow();
        assertThat(llm.getAttrs().toString()).contains("sql_generate", "test-model", "100", "20");

        List<String> reasons = spans.stream()
                .filter(s -> s.getAttrs().toString().contains("\"skip\":true"))
                .map(s -> s.getAttrs().toString())
                .toList();
        assertThat(reasons).allSatisfy(a -> assertThat(a).containsAnyOf(
                "question_complete", "single_table", "raw_data_only", "not_visited"));
    }

    @Test
    @DisplayName("修正循环重复执行 sql_generate 时追加行，不覆盖第一次")
    void retryAppendsAnotherSpan() {
        recorder.begin("t3", "q", null);
        recorder.nodeDone(NodeName.SQL_GENERATE, LocalDateTime.now(), 10L, Map.of("sql", "first"));
        recorder.nodeDone(NodeName.SQL_GENERATE, LocalDateTime.now(), 20L, Map.of("sql", "second"));
        recorder.flush("q", true, "answer");

        List<TraceSpan> generateSpans = capturedSpans().stream()
                .filter(s -> NodeName.SQL_GENERATE.equals(s.getNode()))
                .toList();
        assertThat(generateSpans).hasSize(2);
        assertThat(generateSpans.get(0).getAttrs().toString()).contains("first");
        assertThat(generateSpans.get(1).getAttrs().toString()).contains("second");
    }

    @Test
    @DisplayName("写库失败只记日志，不向主链路抛异常")
    void flushSwallowsPersistenceFailure() {
        doThrow(new RuntimeException("duckdb locked")).when(service).saveBatch(anyList());
        recorder.begin("t4", "q", null);
        recorder.flush("q", false, "error");

        assertThatCode(() -> recorder.flush("q", false, "error")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未 begin 时直接埋点是 no-op，不产生空 trace")
    void noopWithoutBegin() {
        recorder.currentNode(NodeName.CLARIFY);
        recorder.llmCall(1, 1, 1L);
        recorder.nodeDone(NodeName.CLARIFY, LocalDateTime.now(), 1L, Map.of());
        recorder.flush("q", true, "answer");

        verify(service, never()).saveBatch(anyList());
    }
}
