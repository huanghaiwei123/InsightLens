package com.gdou.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 评测跑批汇总表：一次跑批一行，回答"这次正确率多少"
 * @TableName evaluation_run
 */
@Data
@TableName(value ="evaluation_run")
public class EvaluationRun {
    /**
     * 跑批id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 跑批开始时间
     */
    private Date createdAt;

    /**
     * 本次使用的模型标识，如 deepseek-v4-flash
     */
    private String model;

    /**
     * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
     */
    private String codeCommit;

    /**
     * 评测集总题数
     */
    private Integer totalCases;

    /**
     * 通过题数（execution_match = true 的条数）
     */
    private Integer passCount;

    /**
     * 失败题数
     */
    private Integer failCount;

    /**
     * 正确率 = pass_count / total_cases，验收线 >= 0.80
     */
    private Double accuracy;

    /**
     * 编造数字次数，验收线必须 = 0
     */
    private Integer hallucinationCount;

    /**
     * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
     */
    private Integer writeOpBlockedCount;

    /**
     * 平均端到端延迟（毫秒）
     */
    private Double avgLatencyMs;

    /**
     * P95 延迟（毫秒），验收线 < 30000
     */
    private Double p95LatencyMs;

    /**
     * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
     */
    private BigDecimal totalCostYuan;

    /**
     * 备注，如"换 DuckDB 方言后首跑"
     */
    private String notes;

    /**
     * 跑批id（自增主键）
     */
    public Integer getId() {
        return id;
    }

    /**
     * 跑批id（自增主键）
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 跑批开始时间
     */
    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * 跑批开始时间
     */
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 本次使用的模型标识，如 deepseek-v4-flash
     */
    public String getModel() {
        return model;
    }

    /**
     * 本次使用的模型标识，如 deepseek-v4-flash
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
     */
    public String getCodeCommit() {
        return codeCommit;
    }

    /**
     * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
     */
    public void setCodeCommit(String codeCommit) {
        this.codeCommit = codeCommit;
    }

    /**
     * 评测集总题数
     */
    public Integer getTotalCases() {
        return totalCases;
    }

    /**
     * 评测集总题数
     */
    public void setTotalCases(Integer totalCases) {
        this.totalCases = totalCases;
    }

    /**
     * 通过题数（execution_match = true 的条数）
     */
    public Integer getPassCount() {
        return passCount;
    }

    /**
     * 通过题数（execution_match = true 的条数）
     */
    public void setPassCount(Integer passCount) {
        this.passCount = passCount;
    }

    /**
     * 失败题数
     */
    public Integer getFailCount() {
        return failCount;
    }

    /**
     * 失败题数
     */
    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
    }

    /**
     * 正确率 = pass_count / total_cases，验收线 >= 0.80
     */
    public Double getAccuracy() {
        return accuracy;
    }

    /**
     * 正确率 = pass_count / total_cases，验收线 >= 0.80
     */
    public void setAccuracy(Double accuracy) {
        this.accuracy = accuracy;
    }

    /**
     * 编造数字次数，验收线必须 = 0
     */
    public Integer getHallucinationCount() {
        return hallucinationCount;
    }

    /**
     * 编造数字次数，验收线必须 = 0
     */
    public void setHallucinationCount(Integer hallucinationCount) {
        this.hallucinationCount = hallucinationCount;
    }

    /**
     * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
     */
    public Integer getWriteOpBlockedCount() {
        return writeOpBlockedCount;
    }

    /**
     * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
     */
    public void setWriteOpBlockedCount(Integer writeOpBlockedCount) {
        this.writeOpBlockedCount = writeOpBlockedCount;
    }

    /**
     * 平均端到端延迟（毫秒）
     */
    public Double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    /**
     * 平均端到端延迟（毫秒）
     */
    public void setAvgLatencyMs(Double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    /**
     * P95 延迟（毫秒），验收线 < 30000
     */
    public Double getP95LatencyMs() {
        return p95LatencyMs;
    }

    /**
     * P95 延迟（毫秒），验收线 < 30000
     */
    public void setP95LatencyMs(Double p95LatencyMs) {
        this.p95LatencyMs = p95LatencyMs;
    }

    /**
     * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
     */
    public BigDecimal getTotalCostYuan() {
        return totalCostYuan;
    }

    /**
     * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
     */
    public void setTotalCostYuan(BigDecimal totalCostYuan) {
        this.totalCostYuan = totalCostYuan;
    }

    /**
     * 备注，如"换 DuckDB 方言后首跑"
     */
    public String getNotes() {
        return notes;
    }

    /**
     * 备注，如"换 DuckDB 方言后首跑"
     */
    public void setNotes(String notes) {
        this.notes = notes;
    }
}