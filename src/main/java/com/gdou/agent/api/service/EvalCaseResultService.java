package com.gdou.agent.api.service;

import com.gdou.agent.meta.entity.EvalCaseResult;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author huanghaiwei
* @description 针对表【eval_case_result(单条评测明细表：一道题一行，回答"哪题挂了、为什么挂"，积累成回归问题清单)】的数据库操作Service
* @createDate 2026-10-30 17:32:59
*/
public interface EvalCaseResultService extends IService<EvalCaseResult> {

}
