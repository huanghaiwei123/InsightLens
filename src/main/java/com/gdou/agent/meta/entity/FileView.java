package com.gdou.agent.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件注册表：一行 = 一个 sheet；文件是唯一数据源，数据行不入库。
 * 三节点（Clarify/SchemaRetrieve/SqlGuard）按 categoryId 过滤，实现大分类隔离。
 * @TableName file_view
 */
@Data
@TableName(value = "file_view")
@Builder
public class FileView {

    /**
     * 待归类哨兵值：Excel 解析落库时还没有调模型分类，category_id 一律写 -1；
     * 模型归类后由 afterLLmCall 回填真实 category.id。列保持 NOT NULL，不用改库约束。
     */
    public static final Integer PENDING_CATEGORY_ID = -1;

    /**
     * 主键id（自增，取值来自 seq_file_view_id）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 所属大分类，逻辑关联 category.id（应用层保证，不建外键）
     */
    private Integer categoryId;

    /**
     * 文件级 UUID：一个文件一个，多 sheet 共享；删除与文件列表聚合按此定位
     */
    private String uploadId;

    /**
     * 原始文件名（含版本+日期后缀），前端报表展示
     */
    private String origName;

    /**
     * 相对 agent.workspace 的文件路径，直读 read_xlsx 用
     */
    private String filePath;

    /**
     * Excel 内的工作表名，可中文；用户口语提到时靠它定位本行
     */
    private String sheetName;

    /**
     * 本 sheet 行数，回答回显"基于 N 行数据"
     */
    private Long rowCount;

    /**
     * 首行样本，Map 的 toString，形如 {订单明细={order_id=1001,amount=299}}；
     * 上传归类时给模型看已有文件内容，用于按样本列细分
     */
    private String sampleData;

    /**
     * 状态：success / failed；单个 sheet 解析失败不影响同文件其他 sheet
     */
    private String status;

    /**
     * 上传时间，前端报表的时间轴
     */
    private LocalDateTime createdAt;
}
