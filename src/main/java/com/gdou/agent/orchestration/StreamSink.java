package com.gdou.agent.orchestration;

public interface StreamSink {
    void nodeStart(String node);
    void nodeEnd(String node, long ms, AnalysisState state);
    void token(String text);
    void error(String message);
}

