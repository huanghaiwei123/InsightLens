package com.gdou.agent.api.exception;

import com.gdou.agent.api.common.Result;
import com.gdou.agent.api.constant.CodeConstant;
import com.gdou.agent.api.constant.MsgConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底：把异常收敛成与成功响应同构的 Result，前端只需按 code 分支。
 *
 * 回显策略：
 * - 显式指定业务码的 BusinessException：data 回显业务消息，前端可直接展示；
 * - 未指定业务码的 BusinessException / 未预期异常：只回通用文案，
 *   避免把模型返回原文（提示词 + SQL）或系统异常细节透给前端，完整信息只留服务端日志。
 */
@Slf4j
@RestControllerAdvice
public class ExceptionHandle {

    /**
     * 业务相关异常（重试耗尽、模型返回不可解析等），属预期内分支，只记 WARN 不打堆栈。
     * code 由抛出处指定，未指定时回落到通用错误码，便于后续细分业务错误码。
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        String code = e.getCode() == null ? CodeConstant.CALL_ERROR : e.getCode();
        log.warn("[exception] 业务异常 code={}, message={}", code, e.getMessage());
        Object data = e.getCode() == null ? null : e.getMessage();
        return Result.error(code, MsgConstant.CALL_ERROR, data);
    }

    /**
     * 兜底：未预期异常必须带完整堆栈，便于定位是哪个节点抛出来的。
     */
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("[exception] 未预期异常 type={}, message={}",
                e.getClass().getName(), e.getMessage(), e);
        return Result.error(CodeConstant.CALL_ERROR, MsgConstant.CALL_ERROR, null);
    }
}
