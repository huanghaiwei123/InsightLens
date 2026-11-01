package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import java.util.Date;
import java.math.BigDecimal;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* 评测跑批汇总表：一次跑批一行，回答"这次正确率多少"
* @TableName evaluation_run
*/
public class EvaluationRun implements Serializable {

    /**
    * 跑批id（自增主键）
    */
    @NotNull(message="[跑批id（自增主键）]不能为空")
    @ApiModelProperty("跑批id（自增主键）")
    private Integer id;
    /**
    * 跑批开始时间
    */
    @NotNull(message="[跑批开始时间]不能为空")
    @ApiModelProperty("跑批开始时间")
    private Date createdAt;
    /**
    * 本次使用的模型标识，如 deepseek-v4-flash
    */
    @NotBlank(message="[本次使用的模型标识，如 deepseek-v4-flash]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("本次使用的模型标识，如 deepseek-v4-flash")
    @Length(max= 0,message="编码长度不能超过0")
    private String model;
    /**
    * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"")
    @Length(max= 0,message="编码长度不能超过0")
    private String codeCommit;
    /**
    * 评测集总题数
    */
    @NotNull(message="[评测集总题数]不能为空")
    @ApiModelProperty("评测集总题数")
    private Integer totalCases;
    /**
    * 通过题数（execution_match = true 的条数）
    */
    @NotNull(message="[通过题数（execution_match = true 的条数）]不能为空")
    @ApiModelProperty("通过题数（execution_match = true 的条数）")
    private Integer passCount;
    /**
    * 失败题数
    */
    @NotNull(message="[失败题数]不能为空")
    @ApiModelProperty("失败题数")
    private Integer failCount;
    /**
    * 正确率 = pass_count / total_cases，验收线 >= 0.80
    */
    @ApiModelProperty("正确率 = pass_count / total_cases，验收线 >= 0.80")
    private Double accuracy;
    /**
    * 编造数字次数，验收线必须 = 0
    */
    @ApiModelProperty("编造数字次数，验收线必须 = 0")
    private Integer hallucinationCount;
    /**
    * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
    */
    @ApiModelProperty("写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）")
    private Integer writeOpBlockedCount;
    /**
    * 平均端到端延迟（毫秒）
    */
    @ApiModelProperty("平均端到端延迟（毫秒）")
    private Double avgLatencyMs;
    /**
    * P95 延迟（毫秒），验收线 < 30000
    */
    @ApiModelProperty("P95 延迟（毫秒），验收线 < 30000")
    private Double p95LatencyMs;
    /**
    * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
    */
    @ApiModelProperty("本次跑批模型调用总成本（元），单次成本验收线 < 0.1")
    private BigDecimal totalCostYuan;
    /**
    * 备注，如"换 DuckDB 方言后首跑"
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("备注，如"换 DuckDB 方言后首跑"")
    @Length(max= 0,message="编码长度不能超过0")
    private String notes;

    /**
    * 跑批id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 跑批开始时间
    */
    private void setCreatedAt(Date createdAt){
    this.createdAt = createdAt;
    }

    /**
    * 本次使用的模型标识，如 deepseek-v4-flash
    */
    private void setModel(String model){
    this.model = model;
    }

    /**
    * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
    */
    private void setCodeCommit(String codeCommit){
    this.codeCommit = codeCommit;
    }

    /**
    * 评测集总题数
    */
    private void setTotalCases(Integer totalCases){
    this.totalCases = totalCases;
    }

    /**
    * 通过题数（execution_match = true 的条数）
    */
    private void setPassCount(Integer passCount){
    this.passCount = passCount;
    }

    /**
    * 失败题数
    */
    private void setFailCount(Integer failCount){
    this.failCount = failCount;
    }

    /**
    * 正确率 = pass_count / total_cases，验收线 >= 0.80
    */
    private void setAccuracy(Double accuracy){
    this.accuracy = accuracy;
    }

    /**
    * 编造数字次数，验收线必须 = 0
    */
    private void setHallucinationCount(Integer hallucinationCount){
    this.hallucinationCount = hallucinationCount;
    }

    /**
    * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
    */
    private void setWriteOpBlockedCount(Integer writeOpBlockedCount){
    this.writeOpBlockedCount = writeOpBlockedCount;
    }

    /**
    * 平均端到端延迟（毫秒）
    */
    private void setAvgLatencyMs(Double avgLatencyMs){
    this.avgLatencyMs = avgLatencyMs;
    }

    /**
    * P95 延迟（毫秒），验收线 < 30000
    */
    private void setP95LatencyMs(Double p95LatencyMs){
    this.p95LatencyMs = p95LatencyMs;
    }

    /**
    * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
    */
    private void setTotalCostYuan(BigDecimal totalCostYuan){
    this.totalCostYuan = totalCostYuan;
    }

    /**
    * 备注，如"换 DuckDB 方言后首跑"
    */
    private void setNotes(String notes){
    this.notes = notes;
    }


    /**
    * 跑批id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 跑批开始时间
    */
    private Date getCreatedAt(){
    return this.createdAt;
    }

    /**
    * 本次使用的模型标识，如 deepseek-v4-flash
    */
    private String getModel(){
    return this.model;
    }

    /**
    * 代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"
    */
    private String getCodeCommit(){
    return this.codeCommit;
    }

    /**
    * 评测集总题数
    */
    private Integer getTotalCases(){
    return this.totalCases;
    }

    /**
    * 通过题数（execution_match = true 的条数）
    */
    private Integer getPassCount(){
    return this.passCount;
    }

    /**
    * 失败题数
    */
    private Integer getFailCount(){
    return this.failCount;
    }

    /**
    * 正确率 = pass_count / total_cases，验收线 >= 0.80
    */
    private Double getAccuracy(){
    return this.accuracy;
    }

    /**
    * 编造数字次数，验收线必须 = 0
    */
    private Integer getHallucinationCount(){
    return this.hallucinationCount;
    }

    /**
    * 写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）
    */
    private Integer getWriteOpBlockedCount(){
    return this.writeOpBlockedCount;
    }

    /**
    * 平均端到端延迟（毫秒）
    */
    private Double getAvgLatencyMs(){
    return this.avgLatencyMs;
    }

    /**
    * P95 延迟（毫秒），验收线 < 30000
    */
    private Double getP95LatencyMs(){
    return this.p95LatencyMs;
    }

    /**
    * 本次跑批模型调用总成本（元），单次成本验收线 < 0.1
    */
    private BigDecimal getTotalCostYuan(){
    return this.totalCostYuan;
    }

    /**
    * 备注，如"换 DuckDB 方言后首跑"
    */
    private String getNotes(){
    return this.notes;
    }

}
