package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 物理表注册表：每张分析数据表一行，存结构指纹与语义标签，建表前据此判断复用还是新建
 * @TableName table_registry
 */
@Data
@TableName(value = "table_registry")
public class TableRegistry {

    /**
     * 物理表名，小写字母/数字/下划线；撞名追加 _2/_3，后缀由后端算，不让模型数
     */
    @TableId(value = "table_name", type = IdType.INPUT)
    private String tableName;

    /**
     * 结构指纹：用户列名小写 + 类型族，按列名排序后以 | 拼接，如 amount:decimal|order_date:timestamp；
     * 不含 _batch_id，不做哈希；查重走索引
     */
    private String schemaKey;

    /**
     * 语义标签（人可读），如「语文成绩」「数学成绩」；只在新建表时生成一次，
     * 复用表时沿用旧值以防标签漂移；为空表示生成失败，已回退为文件名主干
     */
    private String dataTag;

    /**
     * 建表时间
     */
    private Date createdAt;
}
