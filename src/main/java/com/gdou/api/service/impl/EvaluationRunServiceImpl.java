package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.EvaluationRun;
import com.gdou.api.service.EvaluationRunService;
import com.gdou.api.mapper.EvaluationRunMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【evaluation_run(评测跑批汇总表：一次跑批一行，回答"这次正确率多少")】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class EvaluationRunServiceImpl extends ServiceImpl<EvaluationRunMapper, EvaluationRun>
    implements EvaluationRunService{

}




