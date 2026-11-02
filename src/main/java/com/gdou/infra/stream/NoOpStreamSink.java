package com.gdou.infra.stream;

import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.StreamSink;

public class NoOpStreamSink implements StreamSink {
    public void nodeStart(String node) {}
    public void nodeEnd(String node, long ms, AnalysisState state) {}
    public void token(String text) {}
    public void error(String message) {}
}
