package com.gdou.agent.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表树节点。
 * 根节点对应 category 表的一行（大分类 = workspace 下一级文件夹）；
 * children 是 file_path 里解析出来的小分类目录，可继续嵌套；
 * files 是当前节点直属的文件，同样按 upload_id 聚合。
 * 前端递归渲染：当前节点的 children + files。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryReportVO {

    /** 分类 id；-1 表示数据库中找不到分类的孤儿文件兜底分组 */
    private Integer id;

    /** 分类名，同时就是 workspace 下的目录名 */
    private String name;

    /** 节点创建时间；小分类节点取该目录下最早文件的登记时间 */
    private LocalDateTime createdAt;

    /** 小分类（子目录）节点；大分类节点下可有多层 */
    private List<CategoryReportVO> children;

    /** 去重后的文件数（按 upload_id，含所有子孙节点） */
    private Integer fileCount;

    /** sheet 数，即 file_view 行数 */
    private Integer sheetCount;

    /** 本分类下所有 sheet 的行数之和 */
    private Long totalRows;

    /** 该分类下的文件列表 */
    private List<FileReportVO> files;
}
