package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 可观测链路表：一次请求的一个节点执行一行（接 Langfuse 时可不建）
 * @TableName trace_span
 */
@Data
@TableName(value ="trace_span")
@Builder
public class TraceSpan {
    /**
     * span记录id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 整条链路的唯一标识，同一请求的所有 span 共享
     */
    private String traceId;

    /**
     * 节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize
     */
    private String node;

    /**
     * 节点开始时间（设计表原名 start）
     */
    private LocalDateTime startAt;

    /**
     * 节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断
     */
    private LocalDateTime endAt;

    /**
     * 节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）
     */
    private Object attrs;


}