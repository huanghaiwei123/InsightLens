package com.gdou.agent.infra.stream;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.StreamSink;

public class NoOpStreamSink implements StreamSink {
    public void nodeStart(String node) {}
    public void nodeEnd(String node, long ms, AnalysisState state) {}
    public void token(String text) {}
    public void error(String message) {}
}
