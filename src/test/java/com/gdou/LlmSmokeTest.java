package com.gdou;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：只验证「配置能连通大模型」这一件事。
 * 密钥、base-url、模型名任一写错，这里会立刻暴露，不用等业务代码跑起来。
 */
@SpringBootTest
class LlmSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void shouldReplyWhenApiKeyIsValid() {
        String reply = chatModel.call("只回复两个字：收到");
        System.out.println("=== LLM 回复: " + reply + " ===");
        assertThat(reply).isNotBlank();
        assertThat(reply).contains("收到");
    }
}
