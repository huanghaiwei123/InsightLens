package com.gdou.api.controller;

import com.gdou.api.common.Result;
import com.gdou.infra.LlmClient;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("/personal")
@Slf4j
public class PersonalController {
    @Resource(name = "OpenAiLLmClient")
    public LlmClient openAiLLmClient;

    @PostMapping("/chat")
    public Result chat(@RequestParam("userMsg")String userMsg) {
        return null;
    }
}
