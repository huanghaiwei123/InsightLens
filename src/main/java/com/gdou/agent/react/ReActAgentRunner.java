package com.gdou.agent.react;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.api.tools.FileTool;
import com.gdou.agent.api.tools.FolderTool;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;

@Component
@Slf4j
public class ReActAgentRunner {
    @Autowired
    private  ChatModel chatModel;

    @Autowired
    private  FolderTool folderTool;

    @Autowired
    private  FileTool fileTool;

    /** 联网搜索是增强能力：MCP 连不上时降级为纯本地查数，不能拖垮主链路 */
    @Autowired(required = false)
    private ToolCallbackProvider mcpTools;

    @Autowired
    private GraphRunner runner;

    public Flux<NodeOutput> run(String msg, RunContext ctx){
        // agent 的工具节点在线程池执行，ThreadLocal 的会话上下文捕获在这里（仍在请求线程）
        Integer sessionId = SessionHolder.get();
        Integer categoryId = SessionHolder.getCategory();
        ToolCallback[] searchTools = mcpTools == null
                ? new ToolCallback[0] : mcpTools.getToolCallbacks();
        if (searchTools.length == 0) {
            log.warn("[react] 无可用联网搜索工具，本轮仅使用本地工具");
        }
        ReactAgent agent = ReactAgent.builder()
                .name("insight_lens_ReActAgent")
                .model(chatModel)
                .instruction("""
                        你是数据分析助手，可调用工具查询用户上传的表格数据、管理工作目录中的文件与分类，并在需要时联网检索。

                        【可用工具】
                        - query：查询已上传表格中的数据（数字、指标、趋势、明细）。输入是单个已拆解的数据子问题。
                        - create：在工作目录下创建分类文件夹。输入是相对工作目录的分类路径，可创建多层路径。
                        - transfer：把指定文件移动到目标分类路径。输入为源文件相对路径与目标分类相对路径。
                        - 联网搜索工具：检索表格之外的外部信息与公开资料。表格内已有的数据优先用 query，不要用联网搜索代替查询。

                        【工具协作】
                        - 问题同时涉及本地数据与外部信息时，先用 query 查本地数据，再按需联网检索，最后综合回答。
                        - 涉及文件归类调整时用 create / transfer，操作后简要告知用户结果。

                        【query 返回值解读】
                        query 返回 JSON 字符串，含两个字段：
                        questionToUser（字符串或 null）与 answer（字符串或 null）。
                        - questionToUser 非 null：把该字段文字原样作为最终回复输出（不要输出字段名、不要输出 JSON、不要加引号），立即结束，不再调用任何工具，也不要自行编造或补充答案。
                        - questionToUser 为 null：取 answer 字段内容作为本次查询结果，继续推理或决定下一步。

                        【约束】
                        - 一次只把拆解好的单个数据子问题传给 query。
                        - 数据范围以当前已上传的表格为准。问到没有对应表格或字段的内容，据实说明缺少什么数据，不要猜测或编造。
                        - 分类路径一律使用相对工作目录的路径。
                        """)
                .methodTools(
                        new FileOrFolderTool(folderTool, fileTool),
                        new SqlQueryTool(runner, ctx, sessionId, categoryId)
                )
                .saver(new MemorySaver())
                .tools(searchTools)
                .build();
        try {
            return agent.stream(msg);
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
    }
}
