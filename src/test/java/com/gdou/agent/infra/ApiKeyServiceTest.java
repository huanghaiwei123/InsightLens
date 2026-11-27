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
    @DisplayName("写入 DeepSeek 密钥：缺 deepseek 节点时补在 spring.ai 下，不动 openai")
    void insertsDeepSeekBlock() {
        String result = ApiKeyService.setProperty(YAML, "spring.ai.deepseek.api-key", "sk-deepseek-key");

        // 缩进必须与 openai 同级（4 空格），否则会被解析成 openai 的子节点
        assertThat(result).contains("    deepseek:\n      api-key: 'sk-deepseek-key'");
        assertThat(result).contains("openai:\n      # 取自 Codex 的 auth.json\n      api-key: 'old-secret-key'");
        assertThat(result).containsOnlyOnce("spring:");
        assertThat(result).contains("tavily:\n  api-key: 'tavily-secret'");
    }

    @Test
    @DisplayName("切换主模型：顶层缺 insightlens 节点时整条链补出来，与 spring 平级")
    void insertsProviderAtTopLevel() {
        String result = ApiKeyService.setProperty(YAML, "insightlens.llm.provider", "deepseek");

        assertThat(result).contains("insightlens:\n  llm:\n    provider: 'deepseek'");
        // 新节点必须顶格，否则会挂到上一个顶层节点下面
        assertThat(result).doesNotContain("  insightlens:");
        assertThat(result).contains("spring:\n  ai:");
    }

    @Test
    @DisplayName("重复写入同一路径是幂等的，不会产生第二份 deepseek 节点")
    void rewriteIsIdempotent() {
        String once = ApiKeyService.setProperty(YAML, "spring.ai.deepseek.api-key", "k1");
        String twice = ApiKeyService.setProperty(once, "spring.ai.deepseek.api-key", "k2");

        assertThat(twice).containsOnlyOnce("deepseek:");
        assertThat(twice).contains("api-key: 'k2'");
        assertThat(twice).doesNotContain("k1");
    }

    @Test
    @DisplayName("掩码只保留头尾各 4 位，短 Key 不回显全貌")
    void masksKey() {
        assertThat(ApiKeyService.mask("sk-abcdefghijklmnop")).isEqualTo("sk-a****mnop");
        assertThat(ApiKeyService.mask("short")).isEqualTo("s****");
        assertThat(ApiKeyService.mask("")).isEqualTo("（未配置）");
    }

    @Test
    @DisplayName("安装包模板里的占位 Key 必须当成未配置")
    void treatsTemplatePlaceholderAsMissing() {
        // 占位值的作用只是骗过 OpenAI 自动装配的 Assert，不能让它显示成「已配置」
        assertThat(ApiKeyService.withoutPlaceholder(ApiKeyService.PLACEHOLDER_KEY)).isEmpty();
        assertThat(ApiKeyService.withoutPlaceholder(" " + ApiKeyService.PLACEHOLDER_KEY + " ")).isEmpty();
        assertThat(ApiKeyService.withoutPlaceholder(null)).isEmpty();
        assertThat(ApiKeyService.withoutPlaceholder("sk-real-key-0123456789"))
                .isEqualTo("sk-real-key-0123456789");
    }
}
