package com.gdou.api.exception;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 目前只有jdk自带的异常，并没有定义项目相关的业务异常
 */
@RestControllerAdvice
public class ExceptionHandle {

    /**
     * 兜底处理
     * @param e
     */
    @ExceptionHandler(Exception.class)
    public void handleException(Exception e) {
        throw new RuntimeException(e);
    }
}
