package generator.entity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

import java.io.Serializable;

import java.util.Date;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.validator.constraints.Length;

/**
* 用户会话表：一个会话 = 一段多轮对话
* @TableName session
*/
public class Session implements Serializable {

    /**
    * 用户会话id（自增主键）
    */
    @NotNull(message="[用户会话id（自增主键）]不能为空")
    @ApiModelProperty("用户会话id（自增主键）")
    private Integer id;
    /**
    * 用户会话标题（默认取首条提问前 30 字，可重命名）
    */
    @NotBlank(message="[用户会话标题（默认取首条提问前 30 字，可重命名）]不能为空")
    @Size(max= 0,message="编码长度不能超过0")
    @ApiModelProperty("用户会话标题（默认取首条提问前 30 字，可重命名）")
    @Length(max= 0,message="编码长度不能超过0")
    private String title;
    /**
    * 用户会话创建时间
    */
    @NotNull(message="[用户会话创建时间]不能为空")
    @ApiModelProperty("用户会话创建时间")
    private Date createdAt;
    /**
    * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
    */
    @NotNull(message="[用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）]不能为空")
    @ApiModelProperty("用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）")
    private Date updatedAt;

    /**
    * 用户会话id（自增主键）
    */
    private void setId(Integer id){
    this.id = id;
    }

    /**
    * 用户会话标题（默认取首条提问前 30 字，可重命名）
    */
    private void setTitle(String title){
    this.title = title;
    }

    /**
    * 用户会话创建时间
    */
    private void setCreatedAt(Date createdAt){
    this.createdAt = createdAt;
    }

    /**
    * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
    */
    private void setUpdatedAt(Date updatedAt){
    this.updatedAt = updatedAt;
    }


    /**
    * 用户会话id（自增主键）
    */
    private Integer getId(){
    return this.id;
    }

    /**
    * 用户会话标题（默认取首条提问前 30 字，可重命名）
    */
    private String getTitle(){
    return this.title;
    }

    /**
    * 用户会话创建时间
    */
    private Date getCreatedAt(){
    return this.createdAt;
    }

    /**
    * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
    */
    private Date getUpdatedAt(){
    return this.updatedAt;
    }

}
