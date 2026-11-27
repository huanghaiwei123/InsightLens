package com.gdou.agent.infra.llm;

import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.infra.constant.CodeConstant;

import java.util.Arrays;

/**
 * 可选的大模型厂商。
 *
 * 只描述「配置在哪」与「界面上叫什么」，不关心怎么建 bean ——
 * 建实例的逻辑集中在 {@link com.gdou.agent.infra.ApiKeyService}，
 * 因为切换 provider 与热替换 key 是同一件事的两个入口。
 *
 * 新增厂商时只改这里 + ApiKeyService 的 build()，七天节点与 ReactAgent 不用动：
 * 它们只依赖 LlmClient / chatModel() 这一层抽象。
 */
public enum LlmProvider {

    /**
     * OpenAI 兼容协议。base-url 指向第三方中转（4router），模型名由配置决定。
     * 走 Spring AI 官方 starter 的自动装配，支持 mutate() 热替换 key。
     */
    OPENAI("openai", "OpenAI 兼容", "spring.ai.openai"),

    /**
     * DeepSeek 官方直连。注意两条与 OpenAI 不同的地方：
     * 1. completions 路径是 /chat/completions（不是 /v1/chat/completions），
     *    所以 base-url 只能写 https://api.deepseek.com，带上 /v1 会拼成 /v1/chat/completions 之外的错路径。
     * 2. Spring AI 1.1.2 的 DeepSeekChatModel 没有 mutate()，热替换 key 只能整实例重建。
     */
    DEEPSEEK("deepseek", "DeepSeek", "spring.ai.deepseek");

    private final String id;
    private final String label;
    private final String configPrefix;

    LlmProvider(String id, String label, String configPrefix) {
        this.id = id;
        this.label = label;
        this.configPrefix = configPrefix;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** 该厂商 API Key 在 yaml 里的完整路径，例如 spring.ai.openai.api-key */
    public String apiKeyPath() {
        return configPrefix + ".api-key";
    }

    public String baseUrlPath() {
        return configPrefix + ".base-url";
    }

    public String modelPath() {
        return configPrefix + ".chat.options.model";
    }

    /**
     * 按 id 解析，未知 id 直接抛业务异常。
     * 不做「未知就回退 openai」的兜底 —— 前端传错值时应该立刻暴露，而不是悄悄换模型。
     */
    public static LlmProvider of(String id) {
        if (id != null) {
            String trimmed = id.trim().toLowerCase();
            for (LlmProvider provider : values()) {
                if (provider.id.equals(trimmed)) return provider;
            }
        }
        throw new BusinessException(CodeConstant.CALL_ERROR,
                "不支持的模型厂商：" + id + "，可选值 " + Arrays.toString(Arrays.stream(values()).map(LlmProvider::id).toArray()));
    }
}
