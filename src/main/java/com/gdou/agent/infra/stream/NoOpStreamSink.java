package com.gdou.agent.infra.stream;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.StreamSink;

/**
 * 空对象：同步端点 /chat/overAll 与单元测试使用，丢弃全部节点事件。
 * 让 GraphRunner 可以无脑调 ctx.sink().nodeStart(...)，不必在每个节点判空。
 * 无状态、不注册成 Bean，每次 new 即可。这里刻意不打日志，否则空对象反而制造噪音。
 */
public class NoOpStreamSink implements StreamSink {

    @Override
    public void nodeStart(String node) {}

    @Override
    public void nodeEnd(String node, long ms, AnalysisState state) {}

    @Override
    public void token(String text) {}

    @Override
    public void error(String message) {}
}
