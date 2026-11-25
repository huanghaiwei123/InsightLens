package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.api.constant.CodeConstant;
import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.api.service.FileDownloadService;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.meta.entity.FileView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

@Service
public class FileDownloadServiceImpl implements FileDownloadService {

    @Autowired
    private FileViewService fileViewService;

    @Value("${agent.workspace}")
    private String workSpace;

    @Override
    public DownloadFile resolve(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "缺少文件标识，无法打开");
        }
        List<FileView> views = fileViewService.list(
                new LambdaQueryWrapper<FileView>()
                        .eq(FileView::getUploadId, uploadId)
                        .orderByAsc(FileView::getCreatedAt));
        if (views.isEmpty()) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "文件不存在或已被移除");
        }
        FileView view = views.get(0);
        String relative = view.getFilePath();
        if (relative == null || relative.isBlank()) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "文件尚未归类，无法打开");
        }
        // 评测数据只有元数据，没有真实 Excel；不让伪协议路径漏到兜底异常处理器
        if (relative.startsWith("eval://")) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "评测数据没有对应的 Excel 文件，无法打开");
        }

        Path root = Path.of(workSpace).toAbsolutePath().normalize();
        Path target;
        try {
            target = root.resolve(relative.replace('\\', '/')).normalize();
        } catch (InvalidPathException e) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "文件路径无效，无法打开");
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "文件已不在原位置，无法打开");
        }
        String fileName = view.getOrigName() == null || view.getOrigName().isBlank()
                ? target.getFileName().toString()
                : view.getOrigName();
        return new DownloadFile(target, fileName);
    }
}
