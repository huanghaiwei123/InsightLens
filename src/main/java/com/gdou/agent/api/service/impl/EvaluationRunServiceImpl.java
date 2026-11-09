package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.agent.meta.entity.EvaluationRun;
import com.gdou.agent.api.service.EvaluationRunService;
import com.gdou.agent.api.mapper.EvaluationRunMapper;
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




