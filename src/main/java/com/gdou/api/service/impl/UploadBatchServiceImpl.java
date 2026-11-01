package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.UploadBatch;
import com.gdou.api.service.UploadBatchService;
import com.gdou.api.mapper.UploadBatchMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【upload_batch(Excel 上传批次表：每次上传一行；Excel 是快照不是流，旧批次不删)】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class UploadBatchServiceImpl extends ServiceImpl<UploadBatchMapper, UploadBatch>
    implements UploadBatchService{

}




