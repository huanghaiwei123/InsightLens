package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import java.util.Date;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* 消息表：会话中的每一轮提问 / 回答 / 工具结果
* @TableName message
*/
public class Message implements Serializable {

    /**
    * 消息id（自增主键）
    */
    @NotNull(message="[消息id（自增主键）]不能为空")
    @ApiModelProperty("消息id（自增主键）")
    private Integer id;
    /**
    * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
    */
    @NotNull(message="[所属会话id，逻辑关联 session.id（应用层保证，不建外键）]不能为空")
    @ApiModelProperty("所属会话id，逻辑关联 session.id（应用层保证，不建外键）")
    private Integer sessionId;
    /**
    * 角色：user / assistant / tool / system
    */
    @NotBlank(message="[角色：user / assistant / tool / system]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("角色：user / assistant / tool / system")
    @Length(max= 0,message="编码长度不能超过0")
    private String role;
    /**
    * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
    */
    @NotBlank(message="[消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）")
    @Length(max= 0,message="编码长度不能超过0")
    private String content;
    /**
    * 消息产生时间（同一会话内按此升序拼装上下文）
    */
    @NotNull(message="[消息产生时间（同一会话内按此升序拼装上下文）]不能为空")
    @ApiModelProperty("消息产生时间（同一会话内按此升序拼装上下文）")
    private Date createdAt;

    /**
    * 消息id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
    */
    private void setSessionId(Integer sessionId){
    this.sessionId = sessionId;
    }

    /**
    * 角色：user / assistant / tool / system
    */
    private void setRole(String role){
    this.role = role;
    }

    /**
    * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
    */
    private void setContent(String content){
    this.content = content;
    }

    /**
    * 消息产生时间（同一会话内按此升序拼装上下文）
    */
    private void setCreatedAt(Date createdAt){
    this.createdAt = createdAt;
    }


    /**
    * 消息id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
    */
    private Integer getSessionId(){
    return this.sessionId;
    }

    /**
    * 角色：user / assistant / tool / system
    */
    private String getRole(){
    return this.role;
    }

    /**
    * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
    */
    private String getContent(){
    return this.content;
    }

    /**
    * 消息产生时间（同一会话内按此升序拼装上下文）
    */
    private Date getCreatedAt(){
    return this.createdAt;
    }

}
