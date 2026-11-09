package com.gdou.agent.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OpenAI 专用客户端：只负责「把消息丢给模型、把文本拿回来」。
 * api-key / base-url / 模型名全部由 application.yaml 的 spring.ai.openai.* 提供，
 * ChatModel 由 starter 自动装配好，这里只注入、不创建。
 *
 * 日志口径：INFO 记调用次数、耗时与长度（成本与延迟排查），
 * DEBUG 才打模型原文（含用户数据，避免默认刷屏）；失败一律 ERROR 带堆栈。
 */
@Slf4j
@Service("OpenAiLLmClient")
public class OpenAiLLmClient implements LlmClient {

    private final ChatModel chatModel;

    public OpenAiLLmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String call(List<Message> messages) {
        long start = System.currentTimeMillis();
        int inputChars = messages.stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();
        log.info("[llm] >>> call 消息数={}, 输入字符数={}", messages.size(), inputChars);
        try {
            String text = chatModel.call(new Prompt(messages)).getResult().getOutput().getText();
            log.debug("[llm] 模型返回原文：{}", text);
            log.info("[llm] <<< call 成功 cost={}ms, 返回字符数={}",
                    System.currentTimeMillis() - start, text == null ? 0 : text.length());
            return text;
        } catch (Exception e) {
            log.error("[llm] call 失败 cost={}ms, 消息数={}, 输入字符数={}",
                    System.currentTimeMillis() - start, messages.size(), inputChars, e);
            throw e;
        }
    }

    @Override
    public Flux<String> stream(List<Message> messages) {
        log.info("[llm] >>> stream 消息数={}", messages.size());
        return chatModel.stream(new Prompt(messages))
                .map(response -> response.getResult().getOutput().getText())
                .doOnComplete(() -> log.info("[llm] <<< stream 完成"))
                .doOnError(e -> log.error("[llm] stream 失败", e));
    }
}
