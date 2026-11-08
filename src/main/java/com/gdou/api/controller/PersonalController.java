package com.gdou.api.controller;

import com.gdou.api.common.Result;
import com.gdou.api.constant.CodeConstant;
import com.gdou.api.constant.MsgConstant;
import com.gdou.orchestration.GraphRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("/personal")
@Slf4j
public class PersonalController {
    @Autowired
    private GraphRunner runner;

    @PostMapping("/chat")
    public Result chat(@RequestParam("userMsg")String userMsg) {
        log.info("正在为用户返回查询结果");
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, runner.run(userMsg));
    }

    public Result stream(){
        return null;
    }
}
