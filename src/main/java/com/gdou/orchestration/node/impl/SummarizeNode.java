package com.gdou.orchestration.node.impl;

import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service(NodeName.SUMMARIZE)
public class SummarizeNode implements Node {
    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        boolean fallback = state.getAnswer() == null;
        String answer = fallback ? "大模型调用出错了，请稍后重试" : state.getAnswer();
        state.setAnswer(answer);
        if (fallback) {
            // 兜底不是正常路径：按 traceId 上溯 judge / clarify 的日志定位为什么没有答案
            log.warn("[summarize] answer 为空，使用兜底文案；请上溯本 traceId 的节点日志");
        } else {
            log.info("[summarize] <<< 收口输出 answer={}", answer);
        }
    }
}
