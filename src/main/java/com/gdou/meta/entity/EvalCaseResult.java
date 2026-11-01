package com.gdou.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 单条评测明细表：一道题一行，回答"哪题挂了、为什么挂"，积累成回归问题清单
 * @TableName eval_case_result
 */
@Data
@TableName(value ="eval_case_result")
public class EvalCaseResult {
    /**
     * 明细id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 所属跑批id，逻辑关联 evaluation_run.id
     */
    private Integer runId;

    /**
     * 对应评测集里的题目编号，如 T1-01 / T5-03
     */
    private String caseId;

    /**
     * 原始提问文本
     */
    private String question;

    /**
     * 难度分档：easy / medium / hard
     */
    private String difficulty;

    /**
     * Agent 生成的 SQL（原文保留，用于复盘）
     */
    private String generatedSql;

    /**
     * 标准答案 SQL
     */
    private String goldSql;

    /**
     * 两者执行结果是否一致（评分主依据）
     */
    private Boolean executionMatch;

    /**
     * 该题是否出现编造数字（结论与执行结果不符）
     */
    private Boolean isHallucination;

    /**
     * 该题若为写操作，是否被 SqlGuard 成功拦截
     */
    private Boolean writeOpBlocked;

    /**
     * 该题端到端耗时（毫秒）
     */
    private Double latencyMs;

    /**
     * 该题消耗 token 总数（含多轮调用）
     */
    private Integer tokensUsed;

    /**
     * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
     */
    private String errorType;

    /**
     * 明细id（自增主键）
     */
    public Integer getId() {
        return id;
    }

    /**
     * 明细id（自增主键）
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 所属跑批id，逻辑关联 evaluation_run.id
     */
    public Integer getRunId() {
        return runId;
    }

    /**
     * 所属跑批id，逻辑关联 evaluation_run.id
     */
    public void setRunId(Integer runId) {
        this.runId = runId;
    }

    /**
     * 对应评测集里的题目编号，如 T1-01 / T5-03
     */
    public String getCaseId() {
        return caseId;
    }

    /**
     * 对应评测集里的题目编号，如 T1-01 / T5-03
     */
    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    /**
     * 原始提问文本
     */
    public String getQuestion() {
        return question;
    }

    /**
     * 原始提问文本
     */
    public void setQuestion(String question) {
        this.question = question;
    }

    /**
     * 难度分档：easy / medium / hard
     */
    public String getDifficulty() {
        return difficulty;
    }

    /**
     * 难度分档：easy / medium / hard
     */
    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Agent 生成的 SQL（原文保留，用于复盘）
     */
    public String getGeneratedSql() {
        return generatedSql;
    }

    /**
     * Agent 生成的 SQL（原文保留，用于复盘）
     */
    public void setGeneratedSql(String generatedSql) {
        this.generatedSql = generatedSql;
    }

    /**
     * 标准答案 SQL
     */
    public String getGoldSql() {
        return goldSql;
    }

    /**
     * 标准答案 SQL
     */
    public void setGoldSql(String goldSql) {
        this.goldSql = goldSql;
    }

    /**
     * 两者执行结果是否一致（评分主依据）
     */
    public Boolean getExecutionMatch() {
        return executionMatch;
    }

    /**
     * 两者执行结果是否一致（评分主依据）
     */
    public void setExecutionMatch(Boolean executionMatch) {
        this.executionMatch = executionMatch;
    }

    /**
     * 该题是否出现编造数字（结论与执行结果不符）
     */
    public Boolean getIsHallucination() {
        return isHallucination;
    }

    /**
     * 该题是否出现编造数字（结论与执行结果不符）
     */
    public void setIsHallucination(Boolean isHallucination) {
        this.isHallucination = isHallucination;
    }

    /**
     * 该题若为写操作，是否被 SqlGuard 成功拦截
     */
    public Boolean getWriteOpBlocked() {
        return writeOpBlocked;
    }

    /**
     * 该题若为写操作，是否被 SqlGuard 成功拦截
     */
    public void setWriteOpBlocked(Boolean writeOpBlocked) {
        this.writeOpBlocked = writeOpBlocked;
    }

    /**
     * 该题端到端耗时（毫秒）
     */
    public Double getLatencyMs() {
        return latencyMs;
    }

    /**
     * 该题端到端耗时（毫秒）
     */
    public void setLatencyMs(Double latencyMs) {
        this.latencyMs = latencyMs;
    }

    /**
     * 该题消耗 token 总数（含多轮调用）
     */
    public Integer getTokensUsed() {
        return tokensUsed;
    }

    /**
     * 该题消耗 token 总数（含多轮调用）
     */
    public void setTokensUsed(Integer tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    /**
     * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
     */
    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }
}