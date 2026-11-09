package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ③ SQL 生成节点。用假 LlmClient 返回固定 JSON，不产生任何真实模型调用。
 */
class SqlGenerateNodeTest {

    private static final RunContext CTX = new RunContext("generate-test", new NoOpStreamSink());

    private LlmClient llmClient;
    private SqlGenerateNode node;
    private AnalysisState state;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        node = new SqlGenerateNode(llmClient, new ObjectMapper());
        state = new AnalysisState();
        state.setQuestion("上周退款率");
        state.setClarifiedQuestion("上周各品类退款率");
        state.setCandidateTables(List.of("sales"));
        state.setTableSchemas("CREATE TABLE sales(category VARCHAR, amount DOUBLE);");
    }

    private String userMessage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).call(captor.capture());
        return ((UserMessage) captor.getValue().get(1)).getText();
    }

    @Test
    @DisplayName("正常返回：兼容 ```json 围栏，snake_case 字段映射到 state")
    void parseFencedJson() {
        when(llmClient.call(anyList())).thenReturn(
                "```json\n{\"sql\":\"select category from sales limit 10\",\"sql_explain\":\"按品类查销量\"}\n```");

        node.apply(state, CTX);

        assertThat(state.getSql()).isEqualTo("select category from sales limit 10");
        assertThat(state.getSqlExplain()).isEqualTo("按品类查销量");
        assertThat(userMessage()).contains("澄清后的问题：上周各品类退款率", "候选表：[sales]");
    }

    @Test
    @DisplayName("修正轮把上一轮 SQL 与错误回填给模型，并标明是第几次生成")
    void repairRoundIncludesPreviousSqlAndError() {
        state.setRetryCount(1);
        state.setError("查询了白名单之外的表：secret_table");
        state.setSql("select * from secret_table limit 10");
        when(llmClient.call(anyList())).thenReturn(
                "{\"sql\":\"select * from sales limit 10\",\"sql_explain\":\"改用候选表\"}");

        node.apply(state, CTX);

        String user = userMessage();
        assertThat(user).contains("上一轮生成的 SQL：select * from secret_table limit 10")
                .contains("上一轮的问题：查询了白名单之外的表：secret_table")
                .contains("这是第 2 次生成");
        assertThat(state.getSql()).isEqualTo("select * from sales limit 10");
    }

    @Test
    @DisplayName("retryCount=3 表示 2 次修正已用尽，直接抛业务异常且不再调模型")
    void stopAtRetryLimit() {
        state.setRetryCount(3);

        assertThatThrownBy(() -> node.apply(state, CTX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 次修正");
        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("模型返回里没有 JSON 时抛业务异常")
    void rejectRawWithoutJson() {
        when(llmClient.call(anyList())).thenReturn("抱歉，我无法生成 SQL");

        assertThatThrownBy(() -> node.apply(state, CTX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到 JSON");
    }

    @Test
    @DisplayName("JSON 里 sql 为空时抛业务异常，不让空 SQL 流到 guard")
    void rejectBlankSqlField() {
        when(llmClient.call(anyList())).thenReturn("{\"sql\":\"  \",\"sql_explain\":\"空\"}");

        assertThatThrownBy(() -> node.apply(state, CTX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sql 字段为空");
    }
}
