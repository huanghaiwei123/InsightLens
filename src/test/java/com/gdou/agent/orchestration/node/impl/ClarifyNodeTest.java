package com.gdou.agent.orchestration.node.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.mapper.TableRegistryMapper;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.meta.entity.TableRegistry;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ① 澄清节点：注册表 → 候选表清单 → 模型选表 → state 的映射。
 * Mapper 与 LlmClient 都是假的，不碰 DuckDB、不调模型。
 */
class ClarifyNodeTest {

    private static final RunContext CTX = new RunContext("clarify-test", new NoOpStreamSink());

    private LlmClient llmClient;
    private TableRegistryMapper mapper;
    private ClarifyNode node;
    private AnalysisState state;

    /**
     * clarify 用 LambdaQueryWrapper.select(TableRegistry::getTableName, ...) 拼候选表清单，
     * 方法引用解析依赖 MyBatis-Plus 的 TableInfo 缓存。平时由 Spring 启动时注册，
     * 纯单测没有 SqlSessionFactory，这里手动初始化一次，不需要启动容器或连数据库。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TableRegistry.class);
    }

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        mapper = mock(TableRegistryMapper.class);
        node = new ClarifyNode(llmClient, new ObjectMapper(), mapper);
        state = new AnalysisState();
        state.setQuestion("这次平均分怎么样");
        doReturn(List.of(registry("math_score", "2026 春季期中数学", "class:int|score:decimal"),
                registry("chinese_score", "2026 春季期中语文", "class:int|score:decimal")))
                .when(mapper).selectList(any());
    }

    private static TableRegistry registry(String name, String tag, String schemaKey) {
        TableRegistry t = new TableRegistry();
        t.setTableName(name);
        t.setDataTag(tag);
        t.setSchemaKey(schemaKey);
        return t;
    }

    private String userMessage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).call(captor.capture());
        return ((UserMessage) captor.getValue().get(1)).getText();
    }

    @Test
    @DisplayName("snake_case 响应正确落到 state，selected_tables 映射为 candidateTables")
    void parseClarifyResponse() {
        when(llmClient.call(anyList())).thenReturn("""
                好的，我理解了：
                ```json
                {"clarified_question": "2026 春季期中的平均分", "assumptions": ["按班级平均"],
                 "need_clarify": false, "question_to_user": null, "selected_tables": ["math_score"]}
                ```
                """);

        node.apply(state, CTX);

        assertThat(state.getClarifiedQuestion()).isEqualTo("2026 春季期中的平均分");
        assertThat(state.getAssumptions()).containsExactly("按班级平均");
        assertThat(state.getNeedClarify()).isFalse();
        assertThat(state.getQuestionToUser()).isNull();
        assertThat(state.getCandidateTables()).containsExactly("math_score");
    }

    @Test
    @DisplayName("候选表清单按「表名 | 语义标签 | 结构指纹」三列拼进用户消息")
    void tableListFormat() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"平均分\",\"assumptions\":[],\"need_clarify\":false,"
                        + "\"question_to_user\":null,\"selected_tables\":[\"math_score\"]}");

        node.apply(state, CTX);

        String user = userMessage();
        assertThat(user).contains("候选表清单（表名 | 语义标签 | 结构指纹）")
                .contains("math_score | 2026 春季期中数学 | class:int|score:decimal")
                .contains("chinese_score | 2026 春季期中语文 | class:int|score:decimal")
                .startsWith("这次平均分怎么样");
    }

    @Test
    @DisplayName("need_clarify=true 时把追问写进 state")
    void needClarify() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"\",\"assumptions\":[],\"need_clarify\":true,"
                        + "\"question_to_user\":\"「这次」指哪次考试？[春季期中] [秋季期末]\","
                        + "\"selected_tables\":[]}");

        node.apply(state, CTX);

        assertThat(state.getNeedClarify()).isTrue();
        assertThat(state.getQuestionToUser()).contains("「这次」指哪次考试？");
        assertThat(state.getCandidateTables()).isEmpty();
    }

    @Test
    @DisplayName("selected_tables 缺失时 candidateTables 为空集合而不是 null")
    void missingSelectedTables() {
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"平均分\",\"assumptions\":[],\"need_clarify\":false,"
                        + "\"question_to_user\":null}");

        node.apply(state, CTX);

        assertThat(state.getCandidateTables()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("注册表为空时给出明确占位文案，不让模型对着空清单猜")
    void emptyRegistry() {
        doReturn(List.<TableRegistry>of()).when(mapper).selectList(any());
        when(llmClient.call(anyList())).thenReturn(
                "{\"clarified_question\":\"平均分\",\"assumptions\":[],\"need_clarify\":true,"
                        + "\"question_to_user\":\"请先上传数据\",\"selected_tables\":[]}");

        node.apply(state, CTX);

        assertThat(userMessage()).contains("（库中暂无任何数据表）");
    }
}
