package com.gdou.agent.orchestration.node.impl;

import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ⑦ 收口节点：零模型输出，只负责保证一定有一句话给用户。 */
class SummarizeNodeTest {

    private static final RunContext CTX = new RunContext("summarize-test", new NoOpStreamSink());
    private final SummarizeNode node = new SummarizeNode();

    @Test
    @DisplayName("judge 已给出答案时原样输出，不改写口径")
    void keepExistingAnswer() {
        AnalysisState state = new AnalysisState();
        state.setAnswer("基于 2026 春季期中数据，按班级平均，数学平均分 82 分");

        node.apply(state, CTX);

        assertThat(state.getAnswer()).isEqualTo("基于 2026 春季期中数据，按班级平均，数学平均分 82 分");
    }

    @Test
    @DisplayName("answer 为空时兜底，不让前端拿到 null")
    void fallbackWhenAnswerMissing() {
        AnalysisState state = new AnalysisState();

        node.apply(state, CTX);

        assertThat(state.getAnswer()).isNotBlank().isEqualTo("大模型调用出错了，请稍后重试");
    }
}
