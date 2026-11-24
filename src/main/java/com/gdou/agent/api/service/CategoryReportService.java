package com.gdou.agent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.api.vo.CategoryReportVO;
import com.gdou.agent.api.vo.FileReportVO;
import com.gdou.agent.api.vo.SheetReportVO;
import com.gdou.agent.meta.entity.Category;
import com.gdou.agent.meta.entity.FileView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类报表聚合：大分类(category 表) → 小分类(file_path 里的目录层级) → 文件 → sheet。
 * 只查两张元数据表，不读 Excel 内容，也不碰 DuckDB 业务表。
 *
 * 小分类不从 category 表取——那张表只有大分类；上传时已把真实相对路径
 * （如 销售数据/2026/华东/订单.xlsx）写回 file_view.file_path，这里按它拆目录层级。
 * 历史数据里的 eval:// 等无效路径无法解析，文件直接挂在大分类根节点下。
 */
@Service
public class CategoryReportService {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private FileViewService fileViewService;

    @Value("${agent.workspace}")
    private String workSpace;

    /**
     * 按分类创建时间排序；同一文件按 upload_id 聚合，sheet 按登记时间排序。
     * category 表存在但没有任何文件时仍返回该分类，方便前端展示空状态。
     */
    public List<CategoryReportVO> buildReport() {
        List<Category> categories = categoryService.list(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getCreatedAt));
        List<FileView> views = fileViewService.list(
                new LambdaQueryWrapper<FileView>().orderByAsc(FileView::getCreatedAt));

        Map<Integer, CategoryReportVO> byCategory = new LinkedHashMap<>();
        for (Category category : categories) {
            byCategory.put(category.getId(),
                    newCategory(category.getId(), category.getName(), category.getCreatedAt()));
        }

        // category_id 指向已删除分类时兜底，避免文件在报表里凭空消失
        CategoryReportVO orphan = null;
        Map<String, FileReportVO> files = new HashMap<>();

        for (FileView view : views) {
            CategoryReportVO root = byCategory.get(view.getCategoryId());
            if (root == null) {
                if (orphan == null) {
                    orphan = newCategory(-1, "未分类", null);
                }
                root = orphan;
            }

            String key = root.getId() + "|" + view.getUploadId();
            FileReportVO file = files.get(key);
            if (file == null) {
                file = FileReportVO.builder()
                        .uploadId(view.getUploadId())
                        .origName(view.getOrigName())
                        .createdAt(view.getCreatedAt())
                        .sheets(new ArrayList<>())
                        .build();
                files.put(key, file);

                CategoryReportVO target = descend(root, folderSegments(root, view.getFilePath()));
                target.getFiles().add(file);
                if (target.getCreatedAt() == null && file.getCreatedAt() != null) {
                    target.setCreatedAt(file.getCreatedAt());
                }
            }

            file.getSheets().add(SheetReportVO.builder()
                    .id(view.getId())
                    .sheetName(view.getSheetName())
                    .rowCount(view.getRowCount())
                    .status(view.getStatus())
                    .build());
        }

        List<CategoryReportVO> result = new ArrayList<>(byCategory.values());
        if (orphan != null) {
            result.add(orphan);
        }
        // 每一级节点都要汇总自己 + 所有子孙的统计，前端展开任意层级都能拿到总数
        result.forEach(this::computeStats);
        return result;
    }

    /** 给 Electron 拼本地文件路径用；必须是绝对路径，且分隔符符合当前操作系统 */
    public String workspaceRoot() {
        return Path.of(workSpace).toAbsolutePath().normalize().toString();
    }

    /**
     * 从 file_path 里拆出相对大分类的目录层级。
     * 例：file_path = 销售数据/2026/华东/订单.xlsx，大分类名 = 销售数据
     * → ["2026", "华东"]。
     * 路径不以大分类名开头（历史脏数据如 eval://orders）时返回空，文件挂根节点。
     */
    private List<String> folderSegments(CategoryReportVO root, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return List.of();
        }
        String normalized = rawPath.replace('\\', '/');
        String prefix = root.getName() + "/";
        if (!normalized.startsWith(prefix)) {
            return List.of();
        }
        String rest = normalized.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) {
            return List.of();
        }
        String dir = rest.substring(0, slash);
        return Arrays.stream(dir.split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();
    }

    /** 按目录层级找到/创建节点，返回文件真正归属的那一层 */
    private CategoryReportVO descend(CategoryReportVO parent, List<String> segments) {
        CategoryReportVO node = parent;
        for (String name : segments) {
            CategoryReportVO child = node.getChildren().stream()
                    .filter(item -> name.equals(item.getName()))
                    .findFirst()
                    .orElse(null);
            if (child == null) {
                child = newFolder(name);
                node.getChildren().add(child);
            }
            node = child;
        }
        return node;
    }

    /** 递归汇总：当前节点直属文件 + 所有子节点的统计 */
    private void computeStats(CategoryReportVO node) {
        int fileCount = node.getFiles().size();
        int sheetCount = 0;
        long totalRows = 0L;

        for (FileReportVO file : node.getFiles()) {
            sheetCount += file.getSheets().size();
            for (SheetReportVO sheet : file.getSheets()) {
                if (sheet.getRowCount() != null) {
                    totalRows += sheet.getRowCount();
                }
            }
        }
        for (CategoryReportVO child : node.getChildren()) {
            computeStats(child);
            fileCount += child.getFileCount();
            sheetCount += child.getSheetCount();
            totalRows += child.getTotalRows();
        }

        node.setFileCount(fileCount);
        node.setSheetCount(sheetCount);
        node.setTotalRows(totalRows);
    }

    private CategoryReportVO newCategory(Integer id, String name, LocalDateTime createdAt) {
        return CategoryReportVO.builder()
                .id(id)
                .name(name)
                .createdAt(createdAt)
                .children(new ArrayList<>())
                .fileCount(0)
                .sheetCount(0)
                .totalRows(0L)
                .files(new ArrayList<>())
                .build();
    }

    /** 小分类节点没有 category 表记录，id 留空；统计字段由 computeStats 回填 */
    private CategoryReportVO newFolder(String name) {
        return CategoryReportVO.builder()
                .id(null)
                .name(name)
                .children(new ArrayList<>())
                .fileCount(0)
                .sheetCount(0)
                .totalRows(0L)
                .files(new ArrayList<>())
                .build();
    }
}
