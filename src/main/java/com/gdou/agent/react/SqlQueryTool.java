package com.gdou.agent.react;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.holder.SessionHolder;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class SqlQueryTool {
    private final GraphRunner runner;
    private final RunContext ctx;
    private final ObjectMapper mapper = new ObjectMapper();
    /** SessionHolder 是 ThreadLocal，agent 的工具节点在线程池执行，必须构造时捕获、执行时恢复 */
    private final Integer sessionId;
    private final Integer categoryId;

    public SqlQueryTool(GraphRunner runner, RunContext ctx, Integer sessionId, Integer categoryId) {
        this.runner = runner;
        this.ctx = ctx;
        this.sessionId = sessionId;
        this.categoryId = categoryId;
    }

    @Tool(description = "查询用户上传的 Excel 表格数据。当用户问题涉及具体数字、指标、趋势或明细时调用。"
            + "输入是已拆解好的单个数据子问题。"
            + "返回结果可能是数据答案，也可能是需要向用户澄清的问题。")
    public String query(@ToolParam(description = "用户上传的问题中解析出来需要sql查询的那一部分问题") String msg) {
        SessionHolder.set(sessionId);
        SessionHolder.setCategory(categoryId);
        try {
            return mapper.writeValueAsString(runner.run(msg, ctx));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            SessionHolder.remove();
        }
    }
}
