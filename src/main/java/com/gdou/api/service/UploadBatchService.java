package com.gdou.api.service;

import com.gdou.meta.entity.UploadBatch;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author huanghaiwei
* @description 针对表【upload_batch(Excel 上传批次表：每次上传一行；Excel 是快照不是流，旧批次不删)】的数据库操作Service
* @createDate 2026-10-30 17:32:59
*/
public interface UploadBatchService extends IService<UploadBatch> {

}
