package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大分类：隔离单元，一个分类 = 一个文件夹 = 一个对话窗口
 * @TableName category
 */
@Data
@TableName(value = "category")
@Builder
public class Category {

    /**
     * 分类id（自增主键，取值来自 seq_category_id）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 分类名，唯一；对应 agent.workspace 下的目录名
     */
    private String name;

    /**
     * 分类描述
     */
    private String description;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
