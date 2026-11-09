package com.gdou.agent.orchestration.node;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;

public interface Node {
    void apply(AnalysisState state, RunContext ctx);
}
