package com.gdou.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.service.CategoryReportService;
import com.gdou.agent.service.CategoryService;
import com.gdou.agent.service.FileViewService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分类报表聚合：大分类(category 表) → 小分类(file_path 里的目录层级) → 文件 → sheet。
 * 只查两张元数据表，不读 Excel 内容，也不碰 DuckDB 业务表。
 *
 * 小分类不从 category 表取——那张表只有大分类；上传时已把真实相对路径
 * （如 销售数据/2026/华东/订单.xlsx）写回 file_view.file_path，这里按它拆目录层级。
 * 评测造数用的 eval:// 伪路径没有真实 Excel，不进报表；只含这类文件的分类整类隐藏。
 * 上传解析后尚未归类的文件（category_id=-1）也不展示，避免出现无内容的「未分类」节点。
 */
@Service
public class CategoryReportServiceImpl implements CategoryReportService {

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
    @Override
    public List<CategoryReportVO> buildReport() {
        List<Category> categories = categoryService.list(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getCreatedAt));
        List<FileView> views = fileViewService.list(
                new LambdaQueryWrapper<FileView>().orderByAsc(FileView::getCreatedAt));

        // 评测造数用 eval:// 这类伪路径登记，没有真实 Excel 可打开，也不该出现在分类界面。
        // 统计出「只有伪路径文件」的分类，整类从报表里剔除；真实空分类仍保留供展示。
        Set<Integer> pseudoOnlyCategories = pseudoOnlyCategories(views);

        Map<Integer, CategoryReportVO> byCategory = new LinkedHashMap<>();
        for (Category category : categories) {
            if (pseudoOnlyCategories.contains(category.getId())) {
                continue;
            }
            byCategory.put(category.getId(),
                    newCategory(category.getId(), category.getName(), category.getCreatedAt()));
        }

        Map<String, FileReportVO> files = new HashMap<>();

        for (FileView view : views) {
            // 伪路径文件（评测造数）没有真实文件，不进任何分类树
            if (isPseudoPath(view.getFilePath())) {
                continue;
            }
            // 待归类（-1）或分类已被删除的文件不进报表：它们没有可展示的分类归属，
            // 强行兜底成「未分类」只会让用户误以为这是一个真实分类。
            Integer categoryId = view.getCategoryId();
            if (categoryId == null || FileView.PENDING_CATEGORY_ID.equals(categoryId)) {
                continue;
            }
            CategoryReportVO root = byCategory.get(categoryId);
            if (root == null) {
                continue;
            }

            String key = root.getId() + "|" + view.getUploadId();
            FileReportVO file = files.get(key);
            if (file == null) {
                file = FileReportVO.builder()
                        .uploadId(view.getUploadId())
                        .origName(view.getOrigName())
                        .filePath(view.getFilePath())
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
        // 每一级节点都要汇总自己 + 所有子孙的统计，前端展开任意层级都能拿到总数
        result.forEach(this::computeStats);
        return result;
    }

    /**
     * 找出「只有评测伪路径文件」的分类：出现过伪路径文件、且没有任何真实文件的分类。
     * 这些分类在界面上只会显示一个点不开的假文件，整类隐藏更干净；评测脚本仍按原样读取数据库。
     */
    private Set<Integer> pseudoOnlyCategories(List<FileView> views) {
        Set<Integer> hasPseudo = new HashSet<>();
        Set<Integer> hasReal = new HashSet<>();
        for (FileView view : views) {
            Integer categoryId = view.getCategoryId();
            // -1 是解析后待归类的哨兵值；null 兼容更早的历史数据，都不属于任何真实分类
            if (categoryId == null || FileView.PENDING_CATEGORY_ID.equals(categoryId)) {
                continue;
            }
            (isPseudoPath(view.getFilePath()) ? hasPseudo : hasReal).add(categoryId);
        }
        hasPseudo.removeAll(hasReal);
        return hasPseudo;
    }

    /** eval://orders 这类伪协议路径不是系统里的真实文件，报表和上传提示词都要排除 */
    private boolean isPseudoPath(String rawPath) {
        return rawPath != null && rawPath.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*");
    }

    /** 给 Electron 拼本地文件路径用；必须是绝对路径，且分隔符符合当前操作系统 */
    @Override
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
