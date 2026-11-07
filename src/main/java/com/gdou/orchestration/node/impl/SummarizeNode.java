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
        // TODO 待实现：先说结论，再回显口径假设，写入 answer；期间通过 ctx.sink() 推 token
        String answer = state.getAnswer();
    }
}
