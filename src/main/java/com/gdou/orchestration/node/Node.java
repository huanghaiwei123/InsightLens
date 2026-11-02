package com.gdou.orchestration.node;

import com.gdou.orchestration.AnalysisState;

public interface Node {
    Object apply(AnalysisState state);
}
