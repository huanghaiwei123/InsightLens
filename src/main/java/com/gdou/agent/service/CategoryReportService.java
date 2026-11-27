package com.gdou.agent.service;

import com.gdou.agent.api.vo.CategoryReportVO;

import java.util.List;

/**
 * 分类报表聚合：大分类 → 小分类(file_path 目录层级) → 文件 → sheet。
 * 只查元数据表，不读 Excel 内容，也不碰 DuckDB 业务表。
 */
public interface CategoryReportService {

    /** 构建分类树；分类存在但没有文件时仍返回该分类，供前端展示空状态 */
    List<CategoryReportVO> buildReport();

    /** 给 Electron 拼本地文件路径用的工作目录绝对路径 */
    String workspaceRoot();
}
