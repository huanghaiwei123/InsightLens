package com.gdou.orchestration.node.impl;

import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import org.springframework.stereotype.Service;

@Service(NodeName.SUMMARIZE)
public class SummarizeNode implements Node {
    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        String answer = state.getAnswer() == null ? "大模型调用出错了，请稍后重试" : state.getAnswer();
        state.setAnswer(answer);
    }
}
