package com.gdou.agent.api.controller;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.gdou.agent.infra.common.Result;
import com.gdou.agent.infra.constant.CodeConstant;
import com.gdou.agent.infra.constant.MsgConstant;
import com.gdou.agent.infra.holder.SessionHolder;
import com.gdou.agent.infra.ApiKeyService;
import com.gdou.agent.service.CategoryReportService;
import com.gdou.agent.service.FileDownloadService;
import com.gdou.agent.service.MessageService;
import com.gdou.agent.service.SessionService;
import com.gdou.agent.service.UploadService;
import com.gdou.agent.api.vo.MessageVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.infra.stream.SseStreamSink;
import com.gdou.agent.meta.entity.Session;
import com.gdou.agent.orchestration.GraphRunner;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.react.ReActAgentRunner;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/personal")
public class PersonalController {

    private static final long SSE_TIMEOUT = 120_000L;
    private static final String SAFE_ERROR = "本轮处理失败，请稍后重试";
    private static final String TIMEOUT_MESSAGE = "连接超时，请重试";

    @Autowired
    private GraphRunner runner;

    @Autowired
    @Qualifier("sseExecutor")
    private Executor sseExecutor;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private CategoryReportService categoryReportService;

    @Autowired
    private FileDownloadService fileDownloadService;

    @Autowired
    private ReActAgentRunner reactAgentRunner;

    @Autowired
    private ApiKeyService apiKeyService;

    /** 请求体只接 apiKey；不接 baseUrl，避免误改后整站 404。 */
    public record ApiKeyRequest(String apiKey) {
    }

    /**
     * 用户新建会话窗口，可指定其绑定的大分类。
     * @param categoryId 大分类 id；不传则窗口不绑定分类，检索时不按分类过滤
     * @return 新建窗口的 id
     */
    @GetMapping("/create")
    public Result create(@RequestParam(value = "categoryId", required = false) Integer categoryId) {
        log.info("用户正在新建会话窗口，categoryId={}", categoryId);
        Session session = Session.builder()
                .title("新会话")  // DDL 要求 NOT NULL；标题在首轮对话时再改成提问摘要
                .categoryId(categoryId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        boolean save = sessionService.save(session);
        if(save){
            log.info("用户新建会话窗口完毕，窗口id为{}", session.getId());
            return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, session.getId());
        }else{
            log.error("用户新建会话窗口失败，请检查原因");
            return Result.error(CodeConstant.CALL_ERROR, MsgConstant.CALL_ERROR, null);
        }
    }

    /**
     * 按会话读历史消息，供前端切换分类/页面后回填对话。
     * 只返回 user / assistant 两类正文，按创建时间升序；工具与 system 消息不落库也不会出现。
     */
    @GetMapping("/history")
    public Result history(@RequestParam Integer sessionId) {
        List<com.gdou.agent.meta.entity.Message> rows = messageService.list(
                new LambdaQueryWrapper<com.gdou.agent.meta.entity.Message>()
                        .eq(com.gdou.agent.meta.entity.Message::getSessionId, sessionId)
                        .orderByAsc(com.gdou.agent.meta.entity.Message::getCreatedAt));
        List<MessageVO> data = rows.stream()
                .map(row -> MessageVO.builder().role(row.getRole()).text(row.getContent()).build())
                .toList();
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, data);
    }

    /**
     * 分类报表：category → file(upload_id 去重) → sheet 三层树，供 Electron 客户端渲染。
     * 只查元数据表，不读 Excel 内容，也不触发任何模型调用。
     */
    @GetMapping("/report/categories")
    public Result reportCategories() {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                categoryReportService.buildReport());
    }

    /**
     * 上传文件根目录的绝对路径。Electron 用「workspaceRoot + 分类名 + 原始文件名」
     * 重建本地路径，再交给 shell.showItemInFolder 在资源管理器中定位文件。
     */
    @GetMapping("/config")
    public Result config() {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                Map.of("workspaceRoot", categoryReportService.workspaceRoot()));
    }

    /**
     * 读取当前模型配置。只回显掩码后的 Key —— 明文只在服务端配置文件里，
     * 前端不缓存、接口也不回传，避免 DevTools / 日志里泄露。
     */
    @GetMapping("/settings/api-key")
    public Result apiKeySettings() {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, apiKeyService.status());
    }

    /**
     * 修改 API Key：落盘到 application-local.yaml 并热替换运行中的模型客户端，
     * 用户无需重启。失败原因由 BusinessException 指定业务码，前端可直接回显。
     */
    @PutMapping("/settings/api-key")
    public Result updateApiKey(@RequestBody ApiKeyRequest body) {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                apiKeyService.update(body == null ? null : body.apiKey()));
    }

    /** 切换主模型的请求体：provider 取 openai / deepseek。 */
    public record ProviderRequest(String provider) {
    }

    /** 改某一厂商 Key 的请求体：provider 决定写哪一段配置。 */
    public record ProviderKeyRequest(String provider, String apiKey) {
    }

    /**
     * 读取全部厂商的模型配置：当前主模型、各家的模型名/接口地址/掩码 Key。
     * 一次返回两家，设置页不用按厂商发多次请求。
     */
    @GetMapping("/settings/llm")
    public Result llmSettings() {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                apiKeyService.settings());
    }

    /**
     * 切换主模型：全局生效（所有会话下一轮都走它），选择会落盘，重启后保留。
     * 没配 Key 的厂商会被拒绝 —— 允许切过去只会让用户下一轮提问才看到失败。
     */
    @PutMapping("/settings/llm/provider")
    public Result switchProvider(@RequestBody ProviderRequest body) {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                apiKeyService.switchProvider(body == null ? null : body.provider()));
    }

    /**
     * 修改指定厂商的 API Key：落盘并重建该厂商实例，若它正是主模型则下一轮立即生效。
     * 全局第一把可用的 Key 会自动成为主模型，避免「填完了还是不能用」。
     */
    @PutMapping("/settings/llm/key")
    public Result updateProviderKey(@RequestBody ProviderKeyRequest body) {
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                apiKeyService.updateKey(body == null ? null : body.provider(),
                        body == null ? null : body.apiKey()));
    }

    /**
     * 下载报表里点击的 Excel：只接受 file_view 登记过的 uploadId，
     * 路径越界或文件被移动时由 FileDownloadService 抛业务异常，统一走全局异常兜底。
     */
    @GetMapping("/file")
    public void downloadFile(@RequestParam String uploadId, HttpServletResponse response) throws IOException {
        FileDownloadService.DownloadFile file = fileDownloadService.resolve(uploadId);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8));
        response.setContentLengthLong(Files.size(file.path()));
        Files.copy(file.path(), response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * Excel 上传入口。原导入批次管道（UploadBatchService）已随 upload_batch 表废弃，
     * 新管道尚未实现，此处保留接口契约，明确返回"未实现"而非 null。
     * 后续接入 file_view 直读方案时在此落 落盘 → 解析 sheet → 写 file_view 元数据。
     */
    @PostMapping("/upload")
    public Result upload(@RequestParam("excel") List<MultipartFile> excels) {
        log.warn("用户正在上传{}个文件，正在处理", excels.size());
        return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS,
                uploadService.upload(excels));
    }

//    /**
//     * 用户发起非流式响应对话
//     * @param userMsg
//     * @param sessionId
//     * @return
//     */
//    @PostMapping("/chat/overAll")
//    public Result chat(@RequestParam("userMsg") String userMsg,String sessionId) {
//        Session session = sessionService.getById(sessionId);
//        if (session.getTitle() == null) {
//            session.setTitle(userMsg);
//        }
//        session.setUpdatedAt(LocalDateTime.now());
//        sessionService.updateById(session);
//        SessionHolder.set(session.getId());
//        SessionHolder.setCategory(session.getCategoryId());
//        // traceId 由 ChatInterceptor 在请求线程写入，同一请求的节点日志共用它
//        String traceId = MDC.get(GraphRunner.TRACE_ID);
//        long start = System.currentTimeMillis();
//        log.info("[chat/overAll] >>> 收到请求 traceId={}, userMsg={}, categoryId={}",
//                traceId, userMsg, session.getCategoryId());
//        try {
//            RunContext ctx = new RunContext(traceId, new NoOpStreamSink());
//            GraphReturner returner = runner.run(userMsg, ctx);
//            log.info("[chat/overAll] <<< 完成 traceId={}, cost={}ms",
//                    traceId, System.currentTimeMillis() - start);
//            return Result.success(CodeConstant.CALL_SUCCESS, MsgConstant.CALL_SUCCESS, returner);
//        } catch (Exception e) {
//            // 完整堆栈由 ExceptionHandle 统一记录，这里只留请求上下文，避免同一异常刷两遍
//            log.error("[chat/overAll] 请求失败 traceId={}, cost={}ms, userMsg={}, type={}",
//                    traceId, System.currentTimeMillis() - start, userMsg, e.getClass().getSimpleName());
//            throw e;
//        } finally {
//            // Tomcat 线程会复用：sessionId 与 categoryId 都要清，否则下一个请求会继承上一个窗口的分类
//            SessionHolder.remove();
//        }
//    }

    /**
     * 用户发起流式响应会话
     * @param userMsg
     * @return
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("userMsg") String userMsg,String sessionId) {
        Session session = sessionService.getById(sessionId);
        if (session.getTitle() == null) {
            session.setTitle(userMsg);
        }
        session.setUpdatedAt(LocalDateTime.now());
        sessionService.updateById(session);
        SessionHolder.set(session.getId());
        SessionHolder.setCategory(session.getCategoryId());
        // MDC 是 ThreadLocal：traceId 是值，mdc 是整份上下文快照，后者供子线程日志复用
        String traceId = MDC.get(GraphRunner.TRACE_ID);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        long start = System.currentTimeMillis();
        log.info("[chat/stream] >>> 收到请求 traceId={}, userMsg={}", traceId, userMsg);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // sink 在请求线程创建，超时 / 异常回调才能拿到同一个实例统一收口
        SseStreamSink sink = new SseStreamSink(emitter, traceId);

        // 三个回调由容器线程执行，不继承请求线程的 MDC，需自行重建 traceId
        emitter.onTimeout(() -> withTrace(traceId, () -> {
            log.warn("[chat/stream] 连接超时 traceId={}, cost={}ms",
                    traceId, System.currentTimeMillis() - start);
            sink.fail(TIMEOUT_MESSAGE);
        }));
        emitter.onCompletion(() -> withTrace(traceId,
                () -> log.debug("[chat/stream] 连接结束 traceId={}", traceId)));
        emitter.onError(e -> withTrace(traceId, () -> {
            log.warn("[chat/stream] 连接异常 traceId={}, cause={}",
                    traceId, e.getClass().getSimpleName());
            sink.fail(SAFE_ERROR);
        }));

        try {
            sseExecutor.execute(() -> {
                if (mdc != null) MDC.setContextMap(mdc);

                try {
                    SessionHolder.set(session.getId());
                    SessionHolder.setCategory(session.getCategoryId());
                    // 澄清回答必须带历史：当前这句「春季期中」单独看不是问题，只有连同上一轮
                    // 助手问的「这次指哪次考试」模型才知道在补全什么，从而直接查数作答。
                    // 因此在落当前 user 消息之前先读历史，避免把本轮这条重复带进去。
                    List<Message> history = recentMessages(session.getId());
                    // 用户原话在入口线程池落库：与是否触发 query 工具无关，纯闲聊也记一条
                    saveMessage(session.getId(), userMsg, "user");
                    RunContext ctx = new RunContext(traceId, sink);
                    StringBuilder answer = new StringBuilder();
                    reactAgentRunner.run(userMsg, ctx, history)
                            .doOnNext(nodeOutput -> {
                                if(nodeOutput instanceof StreamingOutput so && so.getOutputType() == OutputType.AGENT_MODEL_STREAMING){
                                    Message msg = so.message();
                                    if(msg instanceof AssistantMessage am && !am.hasToolCalls()){
                                        if(am.getText()!=null){
                                            answer.append(am.getText());
                                            sink.token(am.getText());
                                        }
                                    }
                                }
                            }).blockLast();
                    // 模型最终文本作为 assistant 落库：回答与澄清提问都走 AGENT_MODEL_STREAMING，
                    // 两条路径都会 accumulate 到 answer，落一条即可覆盖「回答或提问」。
                    saveMessage(session.getId(), answer.toString(), "assistant");
                    sink.done();
                    log.info("[chat/stream] <<< 完成 traceId={}, cost={}ms",
                            traceId, System.currentTimeMillis() - start);
                } catch (Exception e) {
                    // 完整堆栈已由 [graph] 执行异常记录，这里只留一条收口摘要，避免重复刷屏
                    log.warn("[chat/stream] <<< 失败 traceId={}, cost={}ms, type={}",
                            traceId, System.currentTimeMillis() - start, e.getClass().getSimpleName());
                    sink.fail(SAFE_ERROR);
                } finally {
                    // 线程池线程会复用，不清会污染下一个请求的 traceId
                    MDC.clear();
                    SessionHolder.remove();
                }
            });
            return emitter;
        } catch (Exception e) {
            log.error("[chat/stream] 提交异步任务失败 traceId={}, userMsg={}, type={}",
                    traceId, userMsg, e.getClass().getSimpleName());
            throw e;
        }
    }

    /** 一轮对话最多带这么多历史消息进提示词，避免长会话把上下文撑爆 */
    private static final int MAX_HISTORY = 12;

    /**
     * 读本会话最近若干轮 user / assistant 消息，转成 Spring AI 的 Message 供 ReAct 理解上下文。
     * 只取 user/assistant——tool 与 system 从不落库，这里天然没有。
     * 按创建时间升序返回（对话顺序），超出上限时只保留最新的 MAX_HISTORY 条。
     */
    private List<Message> recentMessages(Integer sessionId) {
        List<com.gdou.agent.meta.entity.Message> rows = messageService.list(
                new LambdaQueryWrapper<com.gdou.agent.meta.entity.Message>()
                        .eq(com.gdou.agent.meta.entity.Message::getSessionId, sessionId)
                        .in(com.gdou.agent.meta.entity.Message::getRole, "user", "assistant")
                        .orderByDesc(com.gdou.agent.meta.entity.Message::getCreatedAt));
        List<Message> result = new ArrayList<>();
        // rows 按 createdAt 倒序（最新在前）：先取前 MAX_HISTORY 条即最新的，
        // 再从后往前遍历反转成时间正序，模型看到的对话顺序才是对的。
        int to = Math.min(rows.size(), MAX_HISTORY);
        for (int i = to - 1; i >= 0; i--) {
            com.gdou.agent.meta.entity.Message row = rows.get(i);
            if ("assistant".equals(row.getRole())) {
                result.add(new AssistantMessage(row.getContent()));
            } else {
                result.add(new org.springframework.ai.chat.messages.UserMessage(row.getContent()));
            }
        }
        return result;
    }

    /**
     * 把一轮对话的角色消息落到 message 表：入口落 user 原话，模型收口后落 assistant 文本。
     * 工具调用与 system 提示词不落库——前者 trace_span 已有记录，后者是内部提示词资产。
     * content 是 NOT NULL，空白内容直接跳过。
     */
    private void saveMessage(Integer sessionId, String content, String role) {
        if (content == null || content.isBlank()) return;
        com.gdou.agent.meta.entity.Message row = com.gdou.agent.meta.entity.Message.builder()
                .sessionId(sessionId)
                .content(content)
                .role(role)
                .createdAt(LocalDateTime.now())
                .build();
        if (!messageService.save(row)) {
            log.error("[chat/stream] 消息落库失败 sessionId={}, role={}", sessionId, role);
        }
    }

    /**
     * 容器回调线程重建 MDC，让日志前缀 [%X{traceId}] 与 [graph] 链路一致。
     * 必须恢复旧值而不是直接 remove：onCompletion 可能在 sse 线程内同步触发，
     * 直接 remove 会把该线程原有的 traceId 清掉，之后的完成日志就变成 [no-trace]。
     */
    private void withTrace(String traceId, Runnable action) {
        String previous = MDC.get(GraphRunner.TRACE_ID);
        if (traceId != null) MDC.put(GraphRunner.TRACE_ID, traceId);
        try {
            action.run();
        } finally {
            if (previous == null) MDC.remove(GraphRunner.TRACE_ID);
            else MDC.put(GraphRunner.TRACE_ID, previous);
        }
    }
}
