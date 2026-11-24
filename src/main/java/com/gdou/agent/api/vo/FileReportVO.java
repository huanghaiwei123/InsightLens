package com.gdou.agent.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表中的文件节点：按 upload_id 把同一个 xlsx 的多个 sheet 聚合到一起。
 * 前端点文件名时，用「配置接口返回的 workspaceRoot + 分类名 + origName」重建路径，
 * 不要用 file_view.file_path（文件被 transfer 移动后该字段不会更新）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileReportVO {

    /** 文件级 UUID，多 sheet 共享 */
    private String uploadId;

    /** 原始文件名，含版本/日期后缀 */
    private String origName;

    /** 该文件最早一个 sheet 的登记时间 */
    private LocalDateTime createdAt;

    /** 该文件下的 sheet 列表 */
    private List<SheetReportVO> sheets;
}
