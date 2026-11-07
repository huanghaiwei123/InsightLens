package com.gdou.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdou.meta.entity.UploadBatch;

/**
 * @author huanghaiwei
 * @description 针对表【upload_batch(导入批次表：一行 = 一个 sheet 的导入)】的数据库操作Mapper
 * @createDate 2026-11-03 17:33:00
 * @Entity com.gdou.meta.entity.UploadBatch
 */
public interface UploadBatchMapper extends BaseMapper<UploadBatch> {

}
