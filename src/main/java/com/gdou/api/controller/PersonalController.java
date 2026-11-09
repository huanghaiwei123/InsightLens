package com.gdou.api.controller;

import com.gdou.api.common.Result;
import com.gdou.api.constant.CodeConstant;
import com.gdou.api.constant.MsgConstant;
import com.gdou.orchestration.GraphRunner;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/personal")
public class PersonalController {
    @Autowired
    private GraphRunner runner;
    @PostMapping("/chat")
    public Result chat(@RequestParam("userMsg") String userMsg) {
        // 在 HTTP 边界生成 traceId，GraphRunner 复用它，整轮问答的节点日志共用一个 ID
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(GraphRunner.TRACE_ID, traceId);
        long start = System.currentTimeMillis();
        log.info("[chat] >>> 收到请求 traceId={}, userMsg={}", traceId, userMsg);
        try {
            Object data = runner.run(userMsg);
            log.info("[chat] <<< 完成 traceId={}, cost={}ms", traceId, System.currentTimeMillis() - start);
            return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, data);
        } catch (Exception e) {
            log.error("[chat] 请求失败 traceId={}, cost={}ms, userMsg={}",
                    traceId, System.currentTimeMillis() - start, userMsg, e);
            throw e;
        } finally {
            MDC.remove(GraphRunner.TRACE_ID);
        }
    }

    public Result stream() {
        return null;
    }
}
