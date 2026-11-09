package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 消息表：会话中的每一轮提问 / 回答 / 工具结果
 * @TableName message
 */
@Data
@TableName(value ="message")
public class Message {
    /**
     * 消息id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
     */
    private Integer sessionId;

    /**
     * 角色：user / assistant / tool / system
     */
    private String role;

    /**
     * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
     */
    private String content;

    /**
     * 消息产生时间（同一会话内按此升序拼装上下文）
     */
    private Date createdAt;

    /**
     * 消息id（自增主键）
     */
    public Integer getId() {
        return id;
    }

    /**
     * 消息id（自增主键）
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
     */
    public Integer getSessionId() {
        return sessionId;
    }

    /**
     * 所属会话id，逻辑关联 session.id（应用层保证，不建外键）
     */
    public void setSessionId(Integer sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 角色：user / assistant / tool / system
     */
    public String getRole() {
        return role;
    }

    /**
     * 角色：user / assistant / tool / system
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
     */
    public String getContent() {
        return content;
    }

    /**
     * 消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 消息产生时间（同一会话内按此升序拼装上下文）
     */
    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * 消息产生时间（同一会话内按此升序拼装上下文）
     */
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}