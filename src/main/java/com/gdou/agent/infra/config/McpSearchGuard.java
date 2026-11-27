package com.gdou.agent.infra.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 联网搜索（MCP stdio）的启动守卫。
 *
 * <p>背景：Spring AI 的 {@code McpToolCallbackAutoConfiguration} 在刷新上下文时就要
 * 拿到工具列表（{@code mcpToolCallbacks} → {@code mcpSyncClients} → initialize），
 * 只要 stdio 子进程起不来或者握手超时，整个 ApplicationContext 创建就会失败。
 * {@code ReActAgentRunner} 上的 {@code @Autowired(required = false)} 挡不住这一点 ——
 * 那只作用于注入，不作用于 bean 创建。实测一次 20 秒握手超时就能把应用拖死。
 *
 * <p>所以这里在「配置已加载、上下文还没刷新」的窗口里先检查入口脚本是否存在：
 * 不存在就把 {@code spring.ai.mcp.client.enabled} 压成 false，
 * 让应用以「无联网搜索」的形态正常启动，而不是整个起不来。
 */
public class McpSearchGuard implements EnvironmentPostProcessor, Ordered {

    private static final String ENABLED_KEY = "insightlens.mcp.enabled";
    private static final String SCRIPT_KEY = "insightlens.mcp.websearch-script";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Boolean.parseBoolean(environment.getProperty(ENABLED_KEY, "false"))) {
            return;
        }

        String script = environment.getProperty(SCRIPT_KEY, "");
        if (exists(script)) {
            return;
        }

        Map<String, Object> override = new HashMap<>();
        override.put("spring.ai.mcp.client.enabled", "false");
        // addFirst：优先级高于 application.yaml 里的 ${insightlens.mcp.enabled}
        environment.getPropertySources().addFirst(new MapPropertySource("mcpSearchGuard", override));

        // 此时日志系统尚未初始化，直接写 stderr（Electron 会把它收进 backend.log）
        System.err.println("[mcp] 找不到 open-websearch 入口脚本（" + script + "），联网搜索已禁用；"
                + "如需启用，先在 frontend 目录执行 npm run runtime");
    }

    private boolean exists(String script) {
        if (script == null || script.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(script));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        // 必须晚于 ConfigDataEnvironmentPostProcessor，否则读不到 application.yaml 里的值
        return Ordered.LOWEST_PRECEDENCE;
    }
}
