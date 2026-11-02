package com.gdou.infra;

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
 */
@Service("OpenAiLLmClient")
public class OpenAiLLmClient implements LlmClient {

    private final ChatModel chatModel;

    public OpenAiLLmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String call(List<Message> messages) {
        return chatModel.call(new Prompt(messages)).getResult().getOutput().getText();
    }

    @Override
    public Flux<String> stream(List<Message> messages) {
        return chatModel.stream(new Prompt(messages))
                .map(response -> response.getResult().getOutput().getText());
    }
}
