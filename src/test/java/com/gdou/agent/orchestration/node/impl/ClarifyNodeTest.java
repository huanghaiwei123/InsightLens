package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.llm.LlmClient;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ① 澄清节点：file_view → 展示用候选表清单 → 模型补全口径 → state 的映射。
 * 选表已废弃：candidateTables 由 schema_retrieve 全量收集，本节点不再输出 selected_tables。
 * JdbcTemplate 与 LlmClient 都是假的，不碰 DuckDB、不调模型。
 */
class ClarifyNodeTest {

    private static final RunContext CTX = new RunContext("clarify-test", new NoOpStreamSink());

    private LlmClient llmClient;
    private JdbcTemplate jdbc;
    private ClarifyNode node;
    private AnalysisState state;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        jdbc = mock(JdbcTemplate.class);
        node = new ClarifyNode();
        // 节点改成 @Autowired 字段注入后纯单测没有 Spring 容器，手动补齐依赖。
        ReflectionTestUtils.setField(node, "LlmClient", llmClient);
        ReflectionTestUtils.setField(node, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(node, "jdbc", jdbc);
        state = new AnalysisState();
        state.setQuestion("这次平均分怎么样");
        // 窗口未绑定分类 → 走不带 category_id 的查询；返回两行模拟 file_view
        doReturn(List.of(
                view("成绩汇总_2026.xlsx", "春季期中数学", 42L),
                view("成绩汇总_2026.xlsx", "春季期中语文", 42L)))
                .when(jdbc).queryForList(anyString());
    }

    private static Map<String, Object> view(String origName, String sheetName, Long rowCount) {
        return Map.of("orig_name", origName, "sheet_name", sheetName, "row_count", rowCount);
    }

    private String userMessage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).call(captor.capture());
        return ((UserMessage) captor.getValue().get(1)).getText();
    }

    @Test
    @DisplayName("snake_case 响应正确落到 state，且不再解析已废弃的 selected_tables")
    void parseClarifyResponse() {
        when(llmClient.call(anyList())).thenReturn("""
                好的，我理解了：
                ```json
                {"clarified_question": "2026 春季期中的平均分", "assumptions": ["按班级平均"],
                 "need_clarify": false, "question_to_user": null, "selected_tables": ["成绩汇总_2026.xlsx"]}
                ```
                """);

        node.apply(state, CTX);

        assertThat(state.getClarifiedQuestion()).isEqualTo("2026 春季期中的平均分");
        assertThat(state.getAssumptions()).containsExactly("按班级平均");
        assertThat(state.getNeedClarify()).isFalse();
        assertThat(state.getQuestionToUser()).isNull();
        // 模型即使多输出 selected_tables 也被忽略；candidateTables 留给 schema 节点填
        assertThat(state.getCandidateTables()).isNull();
    }

    @Test
    @DisplayName("候选文件清单按「原始文件名 | sheet 名 | 行数」三列拼进用户消息")
    void tableListFormat() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"平均分\",\"assumptions\":[],\"need_clarify\":false,"
                        + "\"question_to_user\":null}");

        node.apply(state, CTX);

        String user = userMessage();
        assertThat(user).contains("候选文件清单（文件名 | sheet 名 | 行数）")
                .contains("成绩汇总_2026.xlsx | 春季期中数学 | 42")
                .contains("成绩汇总_2026.xlsx | 春季期中语文 | 42")
                .startsWith("这次平均分怎么样");
    }

    @Test
    @DisplayName("need_clarify=true 时把追问写进 state")
    void needClarify() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"\",\"assumptions\":[],\"need_clarify\":true,"
                        + "\"question_to_user\":\"「这次」指哪次考试？[春季期中] [秋季期末]\"}");

        node.apply(state, CTX);

        assertThat(state.getNeedClarify()).isTrue();
        assertThat(state.getQuestionToUser()).contains("「这次」指哪次考试？");
    }

    @Test
    @DisplayName("file_view 为空时给出明确占位文案，不让模型对着空清单猜")
    void emptyRegistry() {
        doReturn(List.<Map<String, Object>>of()).when(jdbc).queryForList(anyString());
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"平均分\",\"assumptions\":[],\"need_clarify\":true,"
                        + "\"question_to_user\":\"请先上传数据\"}");

        node.apply(state, CTX);

        assertThat(userMessage()).contains("（库中暂无任何数据表）");
    }
}
