package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import java.util.Date;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* 可观测链路表：一次请求的一个节点执行一行（接 Langfuse 时可不建）
* @TableName trace_span
*/
public class TraceSpan implements Serializable {

    /**
    * span记录id（自增主键）
    */
    @NotNull(message="[span记录id（自增主键）]不能为空")
    @ApiModelProperty("span记录id（自增主键）")
    private Integer id;
    /**
    * 整条链路的唯一标识，同一请求的所有 span 共享
    */
    @NotBlank(message="[整条链路的唯一标识，同一请求的所有 span 共享]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("整条链路的唯一标识，同一请求的所有 span 共享")
    @Length(max= 0,message="编码长度不能超过0")
    private String traceId;
    /**
    * 节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize
    */
    @NotBlank(message="[节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize")
    @Length(max= 0,message="编码长度不能超过0")
    private String node;
    /**
    * 节点开始时间（设计表原名 start）
    */
    @NotNull(message="[节点开始时间（设计表原名 start）]不能为空")
    @ApiModelProperty("节点开始时间（设计表原名 start）")
    private Date startAt;
    /**
    * 节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断
    */
    @ApiModelProperty("节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断")
    private Date endAt;
    /**
    * 节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）
    */
    @ApiModelProperty("节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）")
    private Object attrs;

    /**
    * span记录id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 整条链路的唯一标识，同一请求的所有 span 共享
    */
    private void setTraceId(String traceId){
    this.traceId = traceId;
    }

    /**
    * 节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize
    */
    private void setNode(String node){
    this.node = node;
    }

    /**
    * 节点开始时间（设计表原名 start）
    */
    private void setStartAt(Date startAt){
    this.startAt = startAt;
    }

    /**
    * 节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断
    */
    private void setEndAt(Date endAt){
    this.endAt = endAt;
    }

    /**
    * 节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）
    */
    private void setAttrs(Object attrs){
    this.attrs = attrs;
    }


    /**
    * span记录id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 整条链路的唯一标识，同一请求的所有 span 共享
    */
    private String getTraceId(){
    return this.traceId;
    }

    /**
    * 节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize
    */
    private String getNode(){
    return this.node;
    }

    /**
    * 节点开始时间（设计表原名 start）
    */
    private Date getStartAt(){
    return this.startAt;
    }

    /**
    * 节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断
    */
    private Date getEndAt(){
    return this.endAt;
    }

    /**
    * 节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）
    */
    private Object getAttrs(){
    return this.attrs;
    }

}
