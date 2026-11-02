package com.gdou.infra;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 大模型调用的唯一入口。
 * 七节点只依赖这个接口，不直接碰 ChatModel —— 换厂商时只改实现类。
 */
public interface LlmClient {

    /** 一次性拿完整回复（用于需要 JSON 结果的节点，如 clarify / sql_generate） */
    String call(List<Message> messages);

    /** 流式逐 token 返回（用于 summarize 这种要给用户打字机效果的节点） */
    Flux<String> stream(List<Message> messages);
}
