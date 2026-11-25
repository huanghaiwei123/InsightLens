package com.gdou.agent.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表中的文件节点：按 upload_id 把同一个 xlsx 的多个 sheet 聚合到一起。
 * filePath 是相对 agent.workspace 的路径：Electron 用 workspaceRoot + filePath 拼绝对路径，
 * 浏览器形态则用 uploadId 调 /personal/file 下载。
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

    /** 相对 agent.workspace 的路径，Electron 拼本地绝对路径用 */
    private String filePath;

    /** 该文件最早一个 sheet 的登记时间 */
    private LocalDateTime createdAt;

    /** 该文件下的 sheet 列表 */
    private List<SheetReportVO> sheets;
}
