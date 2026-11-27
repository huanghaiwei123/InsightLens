package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.infra.llm.LlmClient;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ③ SQL 生成节点
 * 入：clarifiedQuestion、fileCatalog（本分类可读 Excel 的路径 + sheet + 列结构）、
 *     tableSchemas（系统表 DDL）、assumptions、上一轮 error（修正循环时）
 * 出：sql、sqlExplain
 */
@Slf4j
@Service(NodeName.SQL_GENERATE)
public class SqlGenerateNode implements Node {

    // 节点依赖统一走字段注入：后续新增 Service 只需加一个 @Autowired 字段，不必改构造参数列表
    @Autowired
    private LlmClient llmClient;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        // retryCount 由 GraphRunner 的 next 方法在回退前 +1，语义是「已发生的回退次数」：
        // 进入本节点时 0=首次生成，1/2=已用掉 1/2 次修正机会，3=三轮生成全失败。
        // 因此修正重试实际上限是 2 次，模型最多生成 3 条 SQL。
        int retryCount = state.getRetryCount() == null ? 0 : state.getRetryCount();
        if (retryCount == 3) {
            log.warn("[sql-generate] 2 次修正已用尽 retryCount={}，终止本轮问答", retryCount);
            throw new BusinessException("SQL 生成重试次数已达上限（2 次修正），终止本轮问答");
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
        sb.append("可读文件清单（只能用这里的路径 + sheet 读，路径必须逐字复制）：\n")
                .append(state.getFileCatalog() == null ? "（本分类暂无可读文件）" : state.getFileCatalog())
                .append('\n');
        sb.append("系统表清单：").append(state.getCandidateTables()).append('\n');
        sb.append("澄清后的问题：").append(state.getClarifiedQuestion()).append('\n');
        sb.append("口径假设：").append(
                state.getAssumptions() == null ? "无" : String.join("；", state.getAssumptions()))
                .append('\n');
        // 上游已判定「这些列能做什么、缺什么」，模型据此规划查询，不用自己从列名反推问题边界
        String assessment = buildAssessment(state);
        if (assessment != null) {
            sb.append(assessment);
        }
        sb.append("系统表结构与说明：\n").append(state.getTableSchemas()).append('\n');
        if (state.getRetryCount() != null && state.getRetryCount() > 0 && state.getError() != null) {
            sb.append("上一轮生成的 SQL：").append(state.getSql()).append('\n');
            sb.append("上一轮的问题：").append(state.getError()).append('\n');
            sb.append("这是第 ").append(state.getRetryCount() + 1)
                    .append(" 次生成，请修正上述问题后重新输出。\n");
        }
        return sb.toString();
    }

    /**
     * 拼「数据可用性评估」块。上游没评估成功（字段全空）时返回 null，
     * 此时输入与旧版完全一致，模型行为不受影响——降级路径不额外增加提示词噪声。
     */
    private String buildAssessment(AnalysisState state) {
        if (state.getDataSufficiency() == null
                && state.getQueryPlan() == null
                && (state.getDataLimitations() == null || state.getDataLimitations().length == 0)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("数据可用性评估（上游已核对真实列，请按此规划查询；缺口只能用现有列近似，"
                + "严禁为缺失项编造列名）：\n");
        sb.append("- 结论：").append(state.getDataSufficiency() == null ? "未评估" : state.getDataSufficiency())
                .append('\n');
        if (state.getDataLimitations() != null && state.getDataLimitations().length > 0) {
            sb.append("- 缺失项：").append(String.join("；", state.getDataLimitations())).append('\n');
        }
        if (state.getQueryPlan() != null) {
            sb.append("- 查询建议：").append(state.getQueryPlan()).append('\n');
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
