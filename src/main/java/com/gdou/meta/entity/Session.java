package com.gdou.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户会话表：一个会话 = 一段多轮对话
 * @TableName session
 */
@Data
@TableName(value ="session")
public class Session {
    /**
     * 用户会话id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 用户会话标题（默认取首条提问前 30 字，可重命名）
     */
    private String title;

    /**
     * 用户会话创建时间
     */
    private Date createdAt;

    /**
     * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
     */
    private Date updatedAt;

    /**
     * 用户会话id（自增主键）
     */
    public Integer getId() {
        return id;
    }

    /**
     * 用户会话id（自增主键）
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 用户会话标题（默认取首条提问前 30 字，可重命名）
     */
    public String getTitle() {
        return title;
    }

    /**
     * 用户会话标题（默认取首条提问前 30 字，可重命名）
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 用户会话创建时间
     */
    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * 用户会话创建时间
     */
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
     */
    public Date getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
     */
    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}