package com.gdou.agent.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.GraphReturner;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SqlQueryTool 是七节点与 ReAct 之间的契约层：把 GraphReturner 序列化成 JSON 回灌给 agent。
 * 这里不启动 Spring、不连库、不调模型，只验证两件事：
 *   1. 编排层拆解后的子问题被原样透传给七节点（不重做意图识别）；
 *   2. 两个出口都序列化成 agent instruction 里约定的字段，且互斥。
 * instruction 依赖这两个字段名做分流，字段名一旦漂移，追问信号就会丢失。
 */
class SqlQueryToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static RunContext testContext() {
        return new RunContext("unit-test", new NoOpStreamSink());
    }

    @Test
    @DisplayName("正常出口：子问题原样透传，answer 进 JSON，questionToUser 为 null")
    void normalAnswerSerializedAsJson() throws Exception {
        GraphRunner runner = mock(GraphRunner.class);
        RunContext ctx = testContext();
        when(runner.run(eq("上周各品类退款率"), same(ctx)))
                .thenReturn(GraphReturner.builder().answer("数码 12.5%，服饰 8.1%").build());

        String json = new SqlQueryTool(runner, ctx).query("上周各品类退款率");

        verify(runner).run(eq("上周各品类退款率"), same(ctx));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("answer").asText()).isEqualTo("数码 12.5%，服饰 8.1%");
        assertThat(node.has("questionToUser")).isTrue();
        assertThat(node.get("questionToUser").isNull()).isTrue();
    }

    @Test
    @DisplayName("澄清出口：questionToUser 非空进 JSON，answer 为 null，供 agent 停下追问")
    void clarifySignalSerializedAsJson() throws Exception {
        GraphRunner runner = mock(GraphRunner.class);
        RunContext ctx = testContext();
        when(runner.run(eq("退款率是多少"), same(ctx)))
                .thenReturn(GraphReturner.builder().questionToUser("请补充统计的时间范围").build());

        String json = new SqlQueryTool(runner, ctx).query("退款率是多少");

        verify(runner).run(eq("退款率是多少"), same(ctx));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("questionToUser").asText()).isEqualTo("请补充统计的时间范围");
        assertThat(node.has("answer")).isTrue();
        assertThat(node.get("answer").isNull()).isTrue();
    }
}
