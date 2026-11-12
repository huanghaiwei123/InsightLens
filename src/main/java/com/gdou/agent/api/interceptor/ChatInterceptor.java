package com.gdou.agent.api.interceptor;

import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.orchestration.GraphRunner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Enumeration;
import java.util.UUID;

@Slf4j
public class ChatInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /**
         * 非控制器请求记得放行
         */
        if(!(handler instanceof HandlerMethod) ){
            return true;
        }
        // 在 HTTP 边界生成 traceId，GraphRunner 复用它，整轮问答的节点日志共用一个 ID
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(GraphRunner.TRACE_ID, traceId);
        log.info("方法运行前拦截器开始执行，生成traceId:{}", traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        MDC.remove(GraphRunner.TRACE_ID);
        SessionHolder.remove();
    }
}
