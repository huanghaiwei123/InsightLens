package com.gdou.orchestration.node;

import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;

public interface Node {
    void apply(AnalysisState state, RunContext ctx);
}
