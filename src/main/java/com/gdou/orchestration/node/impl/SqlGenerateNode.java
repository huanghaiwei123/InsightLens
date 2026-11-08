package com.gdou.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.api.exception.BusinessException;
import com.gdou.infra.LlmClient;
import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        long start = System.currentTimeMillis();
        int retryCount = state.getRetryCount() == null ? 0 : state.getRetryCount();
        if (retryCount == 3) {
            log.warn("[sql-generate] 重试次数已达上限 retryCount={}，终止本轮问答", retryCount);
            throw new BusinessException("SQL 生成重试次数已达上限（3 次），终止本轮问答");
        }
        boolean isRetry = retryCount > 0 && state.getError() != null;
        log.info("[sql-generate] >>> retryCount={}, 修正轮={}, 上一轮问题={}",
                retryCount, isRetry, state.getError());

        String systemMessage = prompt("message/SqlGenerateNode.txt");
        String userMessage = buildUserMessage(state);
        String raw = llmClient.call(List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)));
        log.debug("[sql-generate] 模型返回原文：{}", raw);
        SqlGenerateEntity entity = parseJson(raw);
        state.setSql(entity.sql());
        state.setSqlExplain(entity.sqlExplain());

        // SQL 全文是排障第一现场：guard 拦了什么、execute 跑了什么，都靠这条日志对账
        log.info("[sql-generate] <<< cost={}ms, sql={}, sqlExplain={}",
                System.currentTimeMillis() - start, state.getSql(), state.getSqlExplain());
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
            log.error("[sql-generate] 模型返回中未找到 JSON，raw={}", raw);
            throw new BusinessException("SQL 生成节点：模型返回中未找到 JSON，原文：" + raw);
        }
        String json = raw.substring(start, end + 1);
        try {
            SqlGenerateEntity entity = objectMapper.readValue(json, SqlGenerateEntity.class);
            if (entity.sql() == null || entity.sql().isBlank()) {
                log.error("[sql-generate] 返回的 sql 字段为空，raw={}", raw);
                throw new BusinessException("SQL 生成节点：模型返回的 sql 字段为空，原文：" + raw);
            }
            return entity;
        } catch (JsonProcessingException e) {
            log.error("[sql-generate] JSON 解析失败，raw={}", raw, e);
            throw new BusinessException("SQL 生成节点：模型返回无法解析为 JSON，原文：" + raw);
        }
    }

    /**
     * 从指定路径中解析系统提示词
     */
    private String prompt(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[sql-generate] 提示词读取失败 path={}", path, e);
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
