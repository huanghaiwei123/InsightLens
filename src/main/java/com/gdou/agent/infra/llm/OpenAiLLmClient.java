package com.gdou.agent.infra.llm;

import com.gdou.agent.infra.ApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OpenAI 专用客户端：只负责「把消息丢给模型、把文本拿回来」。
 * api-key / base-url / 模型名初始由 application.yaml + application-local.yaml 提供；
 * 用户在设置页改 Key 后由 {@link ApiKeyService} 热替换模型实例，这里每次调用都取其当前实例。
 *
 * 日志口径：INFO 记调用次数、耗时与长度（成本与延迟排查），
 * DEBUG 才打模型原文（含用户数据，避免默认刷屏）；失败一律 ERROR 带堆栈。
 */
@Slf4j
@Service("OpenAiLLmClient")
public class OpenAiLLmClient implements LlmClient {

    private final ApiKeyService apiKeyService;

    @Autowired
    private TraceRecorder traceRecorder;

    public OpenAiLLmClient(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public String call(List<Message> messages) {
        long start = System.currentTimeMillis();
        int inputChars = messages.stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();
        log.info("[llm] >>> call 消息数={}, 输入字符数={}", messages.size(), inputChars);
        try {
            ChatResponse response = apiKeyService.chatModel().call(new Prompt(messages));
            String text = response.getResult().getOutput().getText();
            long cost = System.currentTimeMillis() - start;
            log.debug("[llm] 模型返回原文：{}", text);
            log.info("[llm] <<< call 成功 cost={}ms, 返回字符数={}", cost, text == null ? 0 : text.length());
            recordCall(cost, response);
            return text;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[llm] call 失败 cost={}ms, 消息数={}, 输入字符数={}", cost, messages.size(), inputChars, e);
            traceRecorder.llmCall(null, null, cost);
            throw e;
        }
    }

    /** token 数取模型返回的真实 usage；中转站不返回时记 null，不用字符数冒充 token。 */
    private void recordCall(long latencyMs, ChatResponse response) {
        Integer tokensIn = null;
        Integer tokensOut = null;
        try {
            ChatResponseMetadata metadata = response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();
            if (usage != null) {
                tokensIn = usage.getPromptTokens();
                tokensOut = usage.getCompletionTokens();
            }
        } catch (Exception e) {
            log.debug("[llm] usage 读取失败，按 null 记：{}", e.getClass().getSimpleName());
        }
        traceRecorder.llmCall(tokensIn, tokensOut, latencyMs);
    }

    @Override
    public Flux<String> stream(List<Message> messages) {
        log.info("[llm] >>> stream 消息数={}", messages.size());
        return apiKeyService.chatModel().stream(new Prompt(messages))
                .map(response -> response.getResult().getOutput().getText())
                .doOnComplete(() -> log.info("[llm] <<< stream 完成"))
                .doOnError(e -> log.error("[llm] stream 失败", e));
    }
}
