package com.gdou.agent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.service.CategoryService;
import com.gdou.agent.service.FileViewService;
import com.gdou.agent.service.impl.CategoryReportServiceImpl;
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
 * 评测造数的 eval:// 伪路径不进报表：有真实文件的分类只隐藏假文件，纯评测分类整类隐藏。
 */
class CategoryReportServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 20, 10, 0);

    @Test
    @DisplayName("file_path 拆出多层小分类，sheet 聚合到文件，评测伪路径不进报表")
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

        CategoryReportServiceImpl service = new CategoryReportServiceImpl();
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

        // eval://orders 是评测造数，不进报表，也不计入任何统计
        assertThat(root.getFiles()).isEmpty();

        assertThat(root.getFileCount()).isEqualTo(2);
        assertThat(root.getSheetCount()).isEqualTo(3);
        assertThat(root.getTotalRows()).isEqualTo(9000L);
    }

    @Test
    @DisplayName("只有评测伪路径文件的分类整类隐藏，真实空分类仍保留")
    void hidePseudoOnlyCategory() {
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                Category.builder().id(1).name("评测数据").createdAt(T).build(),
                Category.builder().id(2).name("销售数据").createdAt(T).build(),
                Category.builder().id(3).name("新建空分类").createdAt(T).build()
        ));
        when(fileViewService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                view(201, "u-eval", "orders", "eval://orders", "orders", 200L),
                viewOf(2, 202, "u-real", "订单.xlsx", "销售数据/订单.xlsx", "Sheet1", 500L)
        ));

        CategoryReportServiceImpl service = new CategoryReportServiceImpl();
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
        ReflectionTestUtils.setField(service, "fileViewService", fileViewService);
        ReflectionTestUtils.setField(service, "workSpace", "D:/tmp/workspace");

        List<CategoryReportVO> report = service.buildReport();

        assertThat(report).extracting(CategoryReportVO::getName)
                .containsExactly("销售数据", "新建空分类");
    }

    @Test
    @DisplayName("待归类文件的 category_id=-1 不进报表，不生成「未分类」节点")
    void pendingFileStaysOutOfReport() {
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                Category.builder().id(1).name("销售数据").createdAt(T).build()
        ));
        when(fileViewService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                viewOf(FileView.PENDING_CATEGORY_ID, 301, "u-pending", "a.xlsx",
                        "temp/a.xlsx", "Sheet1", 10L)
        ));

        CategoryReportServiceImpl service = new CategoryReportServiceImpl();
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
        ReflectionTestUtils.setField(service, "fileViewService", fileViewService);
        ReflectionTestUtils.setField(service, "workSpace", "D:/tmp/workspace");

        List<CategoryReportVO> report = service.buildReport();

        // 真实空分类仍保留；待归类文件不展示，归类成功后会自然出现在对应分类
        assertThat(report).extracting(CategoryReportVO::getName)
                .containsExactly("销售数据");
        assertThat(report.get(0).getFileCount()).isZero();
    }

    private static FileView view(Integer id, String uploadId, String origName,
                                 String filePath, String sheetName, Long rowCount) {
        return viewOf(1, id, uploadId, origName, filePath, sheetName, rowCount);
    }

    private static FileView viewOf(Integer categoryId, Integer id, String uploadId, String origName,
                                   String filePath, String sheetName, Long rowCount) {
        return FileView.builder()
                .id(id)
                .categoryId(categoryId)
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
