package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import org.springframework.cglib.core.Local;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 用户会话表：一个会话 = 一段多轮对话
 * @TableName session
 */
@Data
@TableName(value ="session")
@Builder
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
    private LocalDateTime createdAt;

    /**
     * 用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）
     */
    private LocalDateTime updatedAt;
}