package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.EvalCaseResult;
import com.gdou.api.service.EvalCaseResultService;
import com.gdou.api.mapper.EvalCaseResultMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【eval_case_result(单条评测明细表：一道题一行，回答"哪题挂了、为什么挂"，积累成回归问题清单)】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class EvalCaseResultServiceImpl extends ServiceImpl<EvalCaseResultMapper, EvalCaseResult>
    implements EvalCaseResultService{

}




