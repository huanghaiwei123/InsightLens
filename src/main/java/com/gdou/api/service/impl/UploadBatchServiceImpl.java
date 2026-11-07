package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.api.mapper.UploadBatchMapper;
import com.gdou.api.service.UploadBatchService;
import com.gdou.meta.entity.UploadBatch;
import org.springframework.stereotype.Service;

/**
 * @author huanghaiwei
 * @description 针对表【upload_batch(导入批次表：一行 = 一个 sheet 的导入)】的数据库操作Service实现
 * @createDate 2026-11-03 17:33:00
 */
@Service
public class UploadBatchServiceImpl extends ServiceImpl<UploadBatchMapper, UploadBatch>
        implements UploadBatchService {

}
