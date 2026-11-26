package com.gdou.agent.react;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.api.tools.FileTool;
import com.gdou.agent.api.tools.FolderTool;
import com.gdou.agent.infra.ApiKeyService;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class ReActAgentRunner {
    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private  FolderTool folderTool;

    @Autowired
    private  FileTool fileTool;

    /** 联网搜索是增强能力：MCP 连不上时降级为纯本地查数，不能拖垮主链路 */
    @Autowired(required = false)
    private ToolCallbackProvider mcpTools;

    @Autowired
    private GraphRunner runner;

    public Flux<NodeOutput> run(String msg, RunContext ctx) {
        return run(msg, ctx, List.of());
    }

    /**
     * @param history 本会话此前轮次的 user / assistant 消息，按时间升序。
     *                用户回答澄清时只发一句「春季期中」，必须带上历史模型才知道它在回答哪个问题，
     *                否则会把答案又当成新的一轮去澄清。空列表表示首轮。
     */
    public Flux<NodeOutput> run(String msg, RunContext ctx, List<Message> history) {
        // agent 的工具节点在线程池执行，ThreadLocal 的会话上下文捕获在这里（仍在请求线程）
        Integer sessionId = SessionHolder.get();
        Integer categoryId = SessionHolder.getCategory();
        ToolCallback[] searchTools = mcpTools == null
                ? new ToolCallback[0] : mcpTools.getToolCallbacks();
        if (searchTools.length == 0) {
            log.warn("[react] 无可用联网搜索工具，本轮仅使用本地工具");
        }
        // 当前北京时间注入到系统提示词：默认时区就是北京时间，用户问「现在几点 / 今天几号」时据此回答，
        // 不再以「无法读取实时钟表」为由拒答。时钟只读这一处，精度到分钟（本轮请求时刻）。
        String now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        ReactAgent agent = ReactAgent.builder()
                .name("insight_lens_ReActAgent")
                .model(apiKeyService.chatModel())
                .instruction("""
                        你是数据分析助手，可调用工具查询用户上传的表格数据、管理工作目录中的文件与分类，并在需要时联网检索。

                        【当前时间】
                        现在是 %s（北京时间，UTC+8）。本系统默认时区为北京时间：用户未指明时区的时间问题一律按北京时间理解与换算。
                        用户问「现在几点 / 今天几号 / 当前时间」时，直接基于该时间回答；它精确到分钟、是本轮提问时刻，不是实时走动的钟表，无需再强调无法读取实时时间。

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
                        - questionToUser 非 null：说明这一步确实需要用户补充关键信息。把该字段文字原样作为最终回复输出（不要输出字段名、不要输出 JSON、不要加引号），立即结束，不再调用任何工具，也不要自行编造或补充答案。
                        - questionToUser 为 null：取 answer 字段内容作为本次查询结果，继续推理或决定下一步。

                        【用户回答澄清后：直接给答案，不要再问第二次】
                        对话历史里若你上一轮输出过一条澄清提问（questionToUser），而用户这一轮的回答正是回应它：
                        - 必须先把「用户的原始问题」与「这一轮的回答」拼成一条完整问题，再调用 query 查询并给出数据答案。
                        - 典型例子：你问「「上周」指 11/03-11/09 还是 11/10-11/16？」用户答「11/10-11/16」；此时要传「上周（11/10-11/16）各品类退款率」给 query，而不是再问一次「请告诉我时间范围」。
                        - 用户的回答可能很简短（如只回一个选项、一个日期、一个类名），一律视为对上一轮澄清的补全，不得当成全新独立问题重新澄清。
                        - 补全后 query 仍返回 questionToUser 时，只允许在缺失项真的会把结论方向反转的情况下再问；否则按 query 给出的 answer 直接作答。

                        【约束】
                        - 一次只把拆解好的单个数据子问题传给 query。
                        - 数据范围以当前已上传的表格为准。问到没有对应表格或字段的内容，据实说明缺少什么数据，不要猜测或编造。
                        - 分类路径一律使用相对工作目录的路径。
                        """.formatted(now))
                .methodTools(
                        new FileOrFolderTool(folderTool, fileTool),
                        new SqlQueryTool(runner, ctx, sessionId, categoryId)
                )
                .saver(new MemorySaver())
                .tools(searchTools)
                .build();
        try {
            if (history == null || history.isEmpty()) {
                return agent.stream(msg);
            }
            // 把历史 user/assistant 与当前问题一起作为输入，模型才能理解「只是回答上一轮的澄清」。
            // 每轮 new 的 MemorySaver 不跨请求保存，历史必须在这里显式带进来。
            List<Message> input = new ArrayList<>(history);
            input.add(new org.springframework.ai.chat.messages.UserMessage(msg));
            return agent.stream(input);
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
    }
}
