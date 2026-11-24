package com.gdou.agent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.api.vo.CategoryReportVO;
import com.gdou.agent.meta.entity.Category;
import com.gdou.agent.meta.entity.FileView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证报表树能按 file_path 还原小分类目录层级。
 * category 表只有大分类，小分类必须来自上传后写回的相对路径；
 * 历史脏路径（eval://）要能兜底挂在大分类根节点下，不能丢文件。
 */
class CategoryReportServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 20, 10, 0);

    @Test
    @DisplayName("file_path 拆出多层小分类，sheet 聚合到文件，脏路径挂根节点")
    void buildTreeFromFilePath() {
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.list(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(Category.builder().id(1).name("销售数据").createdAt(T).build()));
        when(fileViewService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                view(101, "u1", "订单.xlsx", "销售数据/2026/华东/订单.xlsx", "Sheet1", 5000L),
                view(102, "u1", "订单.xlsx", "销售数据/2026/华东/订单.xlsx", "Sheet2", 3000L),
                view(103, "u2", "客户.xlsx", "销售数据/2026/华北/客户.xlsx", "Sheet1", 1000L),
                view(104, "u3", "orders", "eval://orders", "orders", 200L)
        ));

        CategoryReportService service = new CategoryReportService();
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
        ReflectionTestUtils.setField(service, "fileViewService", fileViewService);
        ReflectionTestUtils.setField(service, "workSpace", "D:/tmp/workspace");

        List<CategoryReportVO> report = service.buildReport();

        assertThat(report).hasSize(1);
        CategoryReportVO root = report.get(0);
        assertThat(root.getName()).isEqualTo("销售数据");

        assertThat(root.getChildren()).hasSize(1);
        CategoryReportVO year = root.getChildren().get(0);
        assertThat(year.getName()).isEqualTo("2026");
        assertThat(year.getChildren()).extracting(CategoryReportVO::getName)
                .containsExactly("华东", "华北");

        CategoryReportVO east = year.getChildren().get(0);
        assertThat(east.getFiles()).hasSize(1);
        assertThat(east.getFiles().get(0).getOrigName()).isEqualTo("订单.xlsx");
        assertThat(east.getFiles().get(0).getSheets()).hasSize(2);
        assertThat(east.getTotalRows()).isEqualTo(8000L);

        assertThat(root.getFiles()).hasSize(1);
        assertThat(root.getFiles().get(0).getOrigName()).isEqualTo("orders");

        assertThat(root.getFileCount()).isEqualTo(3);
        assertThat(root.getSheetCount()).isEqualTo(4);
        assertThat(root.getTotalRows()).isEqualTo(9200L);
    }

    private static FileView view(Integer id, String uploadId, String origName,
                                 String filePath, String sheetName, Long rowCount) {
        return FileView.builder()
                .id(id)
                .categoryId(1)
                .uploadId(uploadId)
                .origName(origName)
                .filePath(filePath)
                .sheetName(sheetName)
                .rowCount(rowCount)
                .status("success")
                .createdAt(T)
                .build();
    }
}
