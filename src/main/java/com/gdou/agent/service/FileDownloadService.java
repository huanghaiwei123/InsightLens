package com.gdou.agent.service;

import java.nio.file.Path;

/**
 * 已归类 Excel 文件的定位与下载。
 * 只处理 file_view 里登记过的 uploadId，路径必须落在 agent.workspace 内。
 */
public interface FileDownloadService {

    record DownloadFile(Path path, String fileName) {
    }

    /**
     * 按文件级 UUID 找到实际落盘文件。
     *
     * @throws com.gdou.agent.api.exception.BusinessException 文件不存在、未归类或越界时抛出
     */
    DownloadFile resolve(String uploadId);
}
