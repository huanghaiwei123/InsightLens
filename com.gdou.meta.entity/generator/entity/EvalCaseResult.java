package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* 单条评测明细表：一道题一行，回答"哪题挂了、为什么挂"，积累成回归问题清单
* @TableName eval_case_result
*/
public class EvalCaseResult implements Serializable {

    /**
    * 明细id（自增主键）
    */
    @NotNull(message="[明细id（自增主键）]不能为空")
    @ApiModelProperty("明细id（自增主键）")
    private Integer id;
    /**
    * 所属跑批id，逻辑关联 evaluation_run.id
    */
    @NotNull(message="[所属跑批id，逻辑关联 evaluation_run.id]不能为空")
    @ApiModelProperty("所属跑批id，逻辑关联 evaluation_run.id")
    private Integer runId;
    /**
    * 对应评测集里的题目编号，如 T1-01 / T5-03
    */
    @NotBlank(message="[对应评测集里的题目编号，如 T1-01 / T5-03]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("对应评测集里的题目编号，如 T1-01 / T5-03")
    @Length(max= 0,message="编码长度不能超过0")
    private String caseId;
    /**
    * 原始提问文本
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("原始提问文本")
    @Length(max= 0,message="编码长度不能超过0")
    private String question;
    /**
    * 难度分档：easy / medium / hard
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("难度分档：easy / medium / hard")
    @Length(max= 0,message="编码长度不能超过0")
    private String difficulty;
    /**
    * Agent 生成的 SQL（原文保留，用于复盘）
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("Agent 生成的 SQL（原文保留，用于复盘）")
    @Length(max= 0,message="编码长度不能超过0")
    private String generatedSql;
    /**
    * 标准答案 SQL
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("标准答案 SQL")
    @Length(max= 0,message="编码长度不能超过0")
    private String goldSql;
    /**
    * 两者执行结果是否一致（评分主依据）
    */
    @ApiModelProperty("两者执行结果是否一致（评分主依据）")
    private Boolean executionMatch;
    /**
    * 该题是否出现编造数字（结论与执行结果不符）
    */
    @ApiModelProperty("该题是否出现编造数字（结论与执行结果不符）")
    private Boolean isHallucination;
    /**
    * 该题若为写操作，是否被 SqlGuard 成功拦截
    */
    @ApiModelProperty("该题若为写操作，是否被 SqlGuard 成功拦截")
    private Boolean writeOpBlocked;
    /**
    * 该题端到端耗时（毫秒）
    */
    @ApiModelProperty("该题端到端耗时（毫秒）")
    private Double latencyMs;
    /**
    * 该题消耗 token 总数（含多轮调用）
    */
    @ApiModelProperty("该题消耗 token 总数（含多轮调用）")
    private Integer tokensUsed;
    /**
    * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
    */
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空")
    @Length(max= 0,message="编码长度不能超过0")
    private String errorType;

    /**
    * 明细id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 所属跑批id，逻辑关联 evaluation_run.id
    */
    private void setRunId(Integer runId){
    this.runId = runId;
    }

    /**
    * 对应评测集里的题目编号，如 T1-01 / T5-03
    */
    private void setCaseId(String caseId){
    this.caseId = caseId;
    }

    /**
    * 原始提问文本
    */
    private void setQuestion(String question){
    this.question = question;
    }

    /**
    * 难度分档：easy / medium / hard
    */
    private void setDifficulty(String difficulty){
    this.difficulty = difficulty;
    }

    /**
    * Agent 生成的 SQL（原文保留，用于复盘）
    */
    private void setGeneratedSql(String generatedSql){
    this.generatedSql = generatedSql;
    }

    /**
    * 标准答案 SQL
    */
    private void setGoldSql(String goldSql){
    this.goldSql = goldSql;
    }

    /**
    * 两者执行结果是否一致（评分主依据）
    */
    private void setExecutionMatch(Boolean executionMatch){
    this.executionMatch = executionMatch;
    }

    /**
    * 该题是否出现编造数字（结论与执行结果不符）
    */
    private void setIsHallucination(Boolean isHallucination){
    this.isHallucination = isHallucination;
    }

    /**
    * 该题若为写操作，是否被 SqlGuard 成功拦截
    */
    private void setWriteOpBlocked(Boolean writeOpBlocked){
    this.writeOpBlocked = writeOpBlocked;
    }

    /**
    * 该题端到端耗时（毫秒）
    */
    private void setLatencyMs(Double latencyMs){
    this.latencyMs = latencyMs;
    }

    /**
    * 该题消耗 token 总数（含多轮调用）
    */
    private void setTokensUsed(Integer tokensUsed){
    this.tokensUsed = tokensUsed;
    }

    /**
    * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
    */
    private void setErrorType(String errorType){
    this.errorType = errorType;
    }


    /**
    * 明细id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 所属跑批id，逻辑关联 evaluation_run.id
    */
    private Integer getRunId(){
    return this.runId;
    }

    /**
    * 对应评测集里的题目编号，如 T1-01 / T5-03
    */
    private String getCaseId(){
    return this.caseId;
    }

    /**
    * 原始提问文本
    */
    private String getQuestion(){
    return this.question;
    }

    /**
    * 难度分档：easy / medium / hard
    */
    private String getDifficulty(){
    return this.difficulty;
    }

    /**
    * Agent 生成的 SQL（原文保留，用于复盘）
    */
    private String getGeneratedSql(){
    return this.generatedSql;
    }

    /**
    * 标准答案 SQL
    */
    private String getGoldSql(){
    return this.goldSql;
    }

    /**
    * 两者执行结果是否一致（评分主依据）
    */
    private Boolean getExecutionMatch(){
    return this.executionMatch;
    }

    /**
    * 该题是否出现编造数字（结论与执行结果不符）
    */
    private Boolean getIsHallucination(){
    return this.isHallucination;
    }

    /**
    * 该题若为写操作，是否被 SqlGuard 成功拦截
    */
    private Boolean getWriteOpBlocked(){
    return this.writeOpBlocked;
    }

    /**
    * 该题端到端耗时（毫秒）
    */
    private Double getLatencyMs(){
    return this.latencyMs;
    }

    /**
    * 该题消耗 token 总数（含多轮调用）
    */
    private Integer getTokensUsed(){
    return this.tokensUsed;
    }

    /**
    * 失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空
    */
    private String getErrorType(){
    return this.errorType;
    }

}
