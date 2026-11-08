package com.gdou.api.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ExceptionHandle {

    /**
     * 业务相关异常，与 jdk 自带异常进行区分。
     * 只记一条 WARN：预期内的分支（如重试耗尽），不需要堆栈噪音。
     */
    @ExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException e) {
        log.warn("[exception] 业务异常 message={}", e.getMessage());
        throw e;
    }

    /**
     * 兜底处理：未预期异常必须带完整堆栈，便于定位是哪个节点抛出来的。
     */
    @ExceptionHandler(Exception.class)
    public void handleException(Exception e) {
        log.error("[exception] 未预期异常", e);
        throw new RuntimeException(e);
    }
}
