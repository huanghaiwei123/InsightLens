package com.gdou.test.orchestration.node.impl;

import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.impl.SqlExecuteNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * ⑤ 执行节点。JdbcTemplate 是假的，不会连 DuckDB。
 * 重点验证「空结果不在此处硬判 error」，交给 judge 输出场景化说明。
 */
class SqlExecuteNodeTest {

    private static final RunContext CTX = new RunContext("execute-test", new NoOpStreamSink());

    private JdbcTemplate jdbc;
    private SqlExecuteNode node;
    private AnalysisState state;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        node = new SqlExecuteNode(jdbc);
        state = new AnalysisState();
        state.setSql("select category, amount from sales limit 10");
    }

    private void mockRows(List<Object> rows) {
        doReturn(rows).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test
    @DisplayName("把结果集与行数写回 state")
    void writeRowsBack() {
        Map<String, String> row = Map.of("category", "数码", "amount", "1200");
        mockRows(List.<Object>of(row));

        node.apply(state, CTX);

        assertThat(state.getRowCount()).isEqualTo(1);
        assertThat(state.getResultRows()).containsExactly(row);
        assertThat(state.getVerdict()).isNull();
        assertThat(state.getError()).isNull();
    }

    @Test
    @DisplayName("空结果只记 rowCount=0，不设 verdict/error，由 judge 判定 empty")
    void emptyResultDoesNotShortCircuit() {
        mockRows(List.of());

        node.apply(state, CTX);

        assertThat(state.getRowCount()).isZero();
        assertThat(state.getResultRows()).isEmpty();
        assertThat(state.getVerdict()).isNull();
        assertThat(state.getError()).isNull();
    }

    @Test
    @DisplayName("SQL 执行失败时记录 error 与 verdict=error 并向上抛出")
    void markErrorOnFailure() {
        doThrow(new IllegalStateException("syntax error"))
                .when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any());

        assertThatThrownBy(() -> node.apply(state, CTX)).isInstanceOf(RuntimeException.class);
        assertThat(state.getVerdict()).isEqualTo("error");
        assertThat(state.getError()).contains("syntax error");
    }
}
