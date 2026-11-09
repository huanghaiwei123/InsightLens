package com.gdou.test.orchestration.node.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.impl.JudgeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ⑥ 判定节点的三态分派：ok / off_target / empty 对 state 的写法完全不同，
 * 是「编造数字 = 0」与「空结果如实说明」两条验收标准的落点。
 */
class JudgeNodeTest {

    private static final RunContext CTX = new RunContext("judge-test", new NoOpStreamSink());

    private LlmClient llmClient;
    private JudgeNode node;
    private AnalysisState state;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        node = new JudgeNode(llmClient, new ObjectMapper());
        state = new AnalysisState();
        state.setClarifiedQuestion("上周各品类退款率");
        state.setAssumptions(new String[]{"GMV 不含退款"});
        state.setSql("select category, refund_rate from sales limit 10");
        state.setResultRows(List.<Object>of(Map.of("category", "数码", "refund_rate", "0.03")));
        state.setRowCount(1);
    }

    @Test
    @DisplayName("ok：answer 作为最终答案，清掉上一轮 error")
    void verdictOk() {
        state.setError("上一轮答非所问");
        when(llmClient.call(anyList())).thenReturn(
                "```json\n{\"verdict\":\"ok\",\"reason\":null,\"answer\":\"上周数码品类退款率 3%\"}\n```");

        node.apply(state, CTX);

        assertThat(state.getVerdict()).isEqualTo("ok");
        assertThat(state.getAnswer()).isEqualTo("上周数码品类退款率 3%");
        assertThat(state.getError()).isNull();
    }

    @Test
    @DisplayName("off_target：reason 只作为 SQL 修正指令进 error，不回复用户")
    void verdictOffTarget() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"verdict\":\"off_target\",\"reason\":\"缺少 GROUP BY category\",\"answer\":null}");

        node.apply(state, CTX);

        assertThat(state.getVerdict()).isEqualTo("off_target");
        assertThat(state.getError()).isEqualTo("缺少 GROUP BY category");
        assertThat(state.getAnswer()).isNull();
    }

    @Test
    @DisplayName("empty：reason 是面向用户的场景化说明，直接作为答案且不当作失败")
    void verdictEmpty() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"verdict\":\"empty\",\"reason\":\"上周没有退款记录，因此没有可统计的退款率\",\"answer\":null}");

        node.apply(state, CTX);

        assertThat(state.getVerdict()).isEqualTo("empty");
        assertThat(state.getAnswer()).isEqualTo("上周没有退款记录，因此没有可统计的退款率");
        assertThat(state.getError()).isNull();
    }

    @Test
    @DisplayName("已有 verdict 时跳过模型调用")
    void skipWhenVerdictPresent() {
        state.setVerdict("error");
        state.setAnswer("已有答案");

        node.apply(state, CTX);

        assertThat(state.getAnswer()).isEqualTo("已有答案");
        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("模型返回无法解析时抛异常，不静默降级成答案")
    void rejectUnparsableRaw() {
        when(llmClient.call(anyList())).thenReturn("模型拒绝回答");

        assertThatThrownBy(() -> node.apply(state, CTX))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JSON 解析失败");
    }
}
