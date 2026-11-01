package com.gdou.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Excel 上传批次表：每次上传一行；Excel 是快照不是流，旧批次不删
 * @TableName upload_batch
 */
@Data
@TableName(value ="upload_batch")
public class UploadBatch {
    /**
     * 批次记录id（自增主键）
     */
    @TableId
    private Integer id;

    /**
     * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
     */
    private String uploadId;

    /**
     * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
     */
    private String origName;

    /**
     * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
     */
    private String tableNames;

    /**
     * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
     */
    private Long rowCount;

    /**
     * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
     */
    private String filePath;

    /**
     * 状态：pending / parsing / success / failed / deleted
     */
    private String status;

    /**
     * 上传时间；回答时必须回显"基于 X 时间上传的数据"
     */
    private Date createdAt;

    /**
     * 批次记录id（自增主键）
     */
    public Integer getId() {
        return id;
    }

    /**
     * 批次记录id（自增主键）
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
     */
    public String getUploadId() {
        return uploadId;
    }

    /**
     * 批次业务标识（UUID），同时用于生成安全表名 u_<upload_id>_xxx 与数据文件名
     */
    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    /**
     * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
     */
    public String getOrigName() {
        return origName;
    }

    /**
     * 原始文件名（含中文/括号，仅用于展示，绝不直接作为表名）
     */
    public void setOrigName(String origName) {
        this.origName = origName;
    }

    /**
     * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
     */
    public String getTableNames() {
        return tableNames;
    }

    /**
     * 本批次生成的物理表名列表，JSON 数组字符串，如 ["u_ab12_sales","u_ab12_refund"]（一个 sheet 一张表）
     */
    public void setTableNames(String tableNames) {
        this.tableNames = tableNames;
    }

    /**
     * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
     */
    public Long getRowCount() {
        return rowCount;
    }

    /**
     * 导入总行数（全部 sheet 求和），用于回答里回显"基于 N 行数据"
     */
    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    /**
     * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * 对应 DuckDB 数据文件的相对路径，删除批次时据此删文件
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * 状态：pending / parsing / success / failed / deleted
     */
    public String getStatus() {
        return status;
    }

    /**
     * 状态：pending / parsing / success / failed / deleted
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 上传时间；回答时必须回显"基于 X 时间上传的数据"
     */
    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * 上传时间；回答时必须回显"基于 X 时间上传的数据"
     */
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}