package com.gdou.api.exception;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionHandle {

    /**
     * 业务相关异常，与jdk自带异常进行区分
     * @param e
     */
    @ExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException e){
        throw e;
    }


    /**
     * 兜底处理
     * @param e
     */
    @ExceptionHandler(Exception.class)
    public void handleException(Exception e) {
        throw new RuntimeException(e);
    }
}
