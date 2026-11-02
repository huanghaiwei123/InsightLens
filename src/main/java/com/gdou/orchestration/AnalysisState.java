package com.gdou.orchestration;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 分析状态实体类 (AnalysisState)
 */
@Data
public class AnalysisState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户原始问题 */
    private String question;

    /** 澄清后的问题（补全时间范围、指标口径、维度） */
    private String clarifiedQuestion;

    /** 口径假设（如「GMV 不含退款」），最终答案必须回显给用户 */
    private List<String> assumptions;

    /** Schema 检索命中的表名 */
    private List<String> candidateTables;

    /** 命中表的 DDL + 字段注释，拼进提示词 */
    private String tableSchemas;

    /** 当前生成的 SQL */
    private String sql;

    /** 一句话说明这条 SQL 想回答什么 */
    private String sqlExplain;

    /** 安全校验结论：pass / reject + 原因 */
    private String validationResult;

    /** 执行结果集（最多 200 行） */
    private List<Object> resultRows;

    /** 结果行数 */
    private Integer rowCount;

    /** 执行错误信息（SQL 语法 / 超时 / 空结果） */
    private String error;

    /** SQL 修正次数，硬上限 2 */
    private Integer retryCount;

    /** 判定结论：ok / empty / error / off_target */
    private String verdict;

    /** 最终结论 */
    private String answer;

    /** 图表地址（可选） */
    private String chartUrl;
}