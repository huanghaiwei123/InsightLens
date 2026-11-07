package com.gdou.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.infra.LlmClient;
import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ③ SQL 生成节点
 * 入：clarifiedQuestion、candidateTables、tableSchemas、assumptions、上一轮 error（修正循环时）
 * 出：sql、sqlExplain
 */
@Service(NodeName.SQL_GENERATE)
public class SqlGenerateNode implements Node {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public SqlGenerateNode(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        String systemMessage = prompt("message/SqlGenerateNode.txt");
        String userMessage = buildUserMessage(state);
        String raw = llmClient.call(List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)));
        SqlGenerateEntity entity = parseJson(raw);
        state.setSql(entity.sql());
        state.setSqlExplain(entity.sqlExplain());
    }

    /**
     * 拼用户消息。修正循环（judge 打回 / guard 之外的质量问题）时，
     * 把上一轮的 SQL 与错误一并给模型，让它在原文基础上修正而不是盲写。
     */
    private String buildUserMessage(AnalysisState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选表：").append(state.getCandidateTables()).append('\n');
        sb.append("澄清后的问题：").append(state.getClarifiedQuestion()).append('\n');
        sb.append("口径假设：").append(
                state.getAssumptions() == null ? "无" : String.join("；", state.getAssumptions()))
                .append('\n');
        sb.append("表结构：\n").append(state.getTableSchemas()).append('\n');
        if (state.getRetryCount() != null && state.getRetryCount() > 0 && state.getError() != null) {
            sb.append("上一轮生成的 SQL：").append(state.getSql()).append('\n');
            sb.append("上一轮的问题：").append(state.getError()).append('\n');
            sb.append("这是第 ").append(state.getRetryCount() + 1)
                    .append(" 次生成，请修正上述问题后重新输出。\n");
        }
        return sb.toString();
    }

    private SqlGenerateEntity parseJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new RuntimeException("SQL 生成节点：模型返回中未找到 JSON，原文：" + raw);
        }
        String json = raw.substring(start, end + 1);
        try {
            SqlGenerateEntity entity = objectMapper.readValue(json, SqlGenerateEntity.class);
            if (entity.sql() == null || entity.sql().isBlank()) {
                throw new RuntimeException("SQL 生成节点：模型返回的 sql 字段为空，原文：" + raw);
            }
            return entity;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("SQL 生成节点：模型返回无法解析为 JSON，原文：" + raw, e);
        }
    }

    /**
     * 从指定路径中解析系统提示词
     */
    private String prompt(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

/**
 * sql_generate 节点的 LLM 返回结构。
 * 提示词要求 snake_case，这里必须显式映射：
 * Jackson 默认按字段名精确匹配，不写 @JsonProperty 会静默得到全 null。
 */
record SqlGenerateEntity (
    /** 当前生成的 SQL */
    @JsonProperty("sql")
    String sql,

    /** 一句话说明这条 SQL 想回答什么 */
    @JsonProperty("sql_explain")
    String sqlExplain
){}
