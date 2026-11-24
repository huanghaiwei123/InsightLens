package com.gdou.agent.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报表中的单个 sheet 节点。
 * 一行 file_view 对应一个 sheet，所以这里直接映射，不做聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SheetReportVO {

    /** file_view.id，后续若要按 sheet 查明细可用 */
    private Integer id;

    /** Excel 内的工作表名，可中文 */
    private String sheetName;

    /** 本 sheet 的数据行数 */
    private Long rowCount;

    /** success / failed */
    private String status;
}
