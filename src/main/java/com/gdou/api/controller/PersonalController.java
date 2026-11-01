package com.gdou.api.controller;

import com.gdou.api.common.Result;
import com.gdou.api.constant.CodeConstant;
import com.gdou.infra.LlmClient;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/personal")
@Slf4j
public class PersonalController {
    @Resource(name = "OpenAiLLmClient")
    public LlmClient openAiLLmClient;

    @PostMapping("")
    public Result personal(String userMsg) {
        log.info("用户调用chat接口中，问题：{}", userMsg);
        Object res = openAiLLmClient.run(userMsg);
        log.info("personal result: {}", res);
        return Result.success(CodeConstant.CALL_SUCCESS,CodeConstant.CALL_SUCCESS,res);
    }
}
