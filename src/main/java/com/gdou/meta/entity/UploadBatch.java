package com.gdou.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 导入批次表：一行 = 一个 sheet 的导入；单 sheet 文件即一行一个文件，
 * 多 sheet 文件拆多行共享 upload_id。删文件只删数据行、不删表结构
 * @TableName upload_batch
 */
@Data
@TableName(value = "upload_batch")
public class UploadBatch {

    /**
     * 导入记录id（自增主键，取值来自 seq_upload_batch_id），
     * 即物理表 _batch_id 系统列的取值
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 文件级标识（UUID）：一个文件一个，多 sheet 时多行共享。
     * 前端文件列表按此聚合、删除按此唯一定位
     */
    private String uploadId;

    /**
     * 数据写入的物理表名，逻辑关联 table_registry.table_name（应用层保证，不建外键）
     */
    private String tableName;

    /**
     * 原始文件名 + 批次后缀：<原文件名>_v<序号>_<yyyymmdd>.<ext>，如 语文成绩_v2_20261103.xlsx；
     * 后缀既让用户区分同名的多次上传，也让模型识别这是同一类文件的第几次
     */
    private String origName;

    /**
     * sheet 名；单 sheet 文件可为空
     */
    private String sheetName;

    /**
     * 本 sheet 导入行数，回答里回显"基于 N 行数据"；按表汇总用 SUM 实时算，不冗余存储
     */
    private Long rowCount;

    /**
     * 状态：pending / success / failed。不做软删除标记，删文件直接删行
     */
    private String status;

    /**
     * 导入时间，回答时回显"基于 X 时间上传的数据"。注意这是上传时间，不是数据自身的时间范围
     */
    private Date createdAt;
}
