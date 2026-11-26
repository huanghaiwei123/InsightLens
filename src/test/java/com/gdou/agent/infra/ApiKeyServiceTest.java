package com.gdou.agent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只测纯字符串改写与掩码，不启动 Spring 上下文、不碰真实密钥文件。
 * 期望：注释保留、其他厂商密钥不受影响、缺失节点时能补出来。
 */
class ApiKeyServiceTest {

    private static final String YAML = """
            # ============================================================
            # 本地私有配置
            # ============================================================
            spring:
              ai:
                openai:
                  # 取自 Codex 的 auth.json
                  api-key: 'old-secret-key'
                  base-url: https://4router.net

            tavily:
              api-key: 'tavily-secret'
            """;

    @Test
    @DisplayName("只替换 spring.ai.openai.api-key，注释与 tavily 密钥不动")
    void replacesOnlyOpenAiKey() {
        String result = ApiKeyService.replaceOpenAiApiKey(YAML, "new-secret-key");

        assertThat(result).contains("api-key: 'new-secret-key'");
        assertThat(result).doesNotContain("old-secret-key");
        assertThat(result).contains("api-key: 'tavily-secret'");
        assertThat(result).contains("# 取自 Codex 的 auth.json");
        assertThat(result).contains("base-url: https://4router.net");
    }

    @Test
    @DisplayName("缺少 api-key 行时插在 openai 块内，不新增重复的 spring 节点")
    void insertsMissingKeyInsideOpenAiBlock() {
        String yaml = """
                spring:
                  ai:
                    openai:
                      base-url: https://4router.net
                tavily:
                  api-key: 'keep-me'
                """;
        String result = ApiKeyService.replaceOpenAiApiKey(yaml, "abc12345");

        assertThat(result).contains("    openai:\n      api-key: 'abc12345'\n      base-url:");
        assertThat(result).contains("tavily:\n  api-key: 'keep-me'");
        assertThat(result).containsOnlyOnce("spring:");
    }

    @Test
    @DisplayName("空文件时能生成最小可用配置")
    void createsMinimalConfig() {
        assertThat(ApiKeyService.replaceOpenAiApiKey("", "abc12345"))
                .isEqualTo("spring:\n  ai:\n    openai:\n      api-key: 'abc12345'");
    }

    @Test
    @DisplayName("掩码只保留头尾各 4 位，短 Key 不回显全貌")
    void masksKey() {
        assertThat(ApiKeyService.mask("sk-abcdefghijklmnop")).isEqualTo("sk-a****mnop");
        assertThat(ApiKeyService.mask("short")).isEqualTo("s****");
        assertThat(ApiKeyService.mask("")).isEqualTo("（未配置）");
    }
}
