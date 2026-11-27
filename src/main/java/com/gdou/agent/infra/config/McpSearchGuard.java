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
 * <p>职责：在「配置已加载、上下文还没刷新」的窗口里确定入口脚本的真实位置。
 * <ol>
 *   <li>Electron 传了 {@code -Dinsightlens.mcp.websearch-script} 且文件存在 → 直接用；</li>
 *   <li>没传但项目里跑过 {@code npm run runtime} → 自动认领 frontend/runtime 下的入口；</li>
 *   <li>都没有 → 把 {@code insightlens.mcp.enabled} 压成 false，不建客户端。</li>
 * </ol>
 *
 * <p>三点必须压掉开关而不是让它在运行期失败：{@link McpSearchConfig} 的客户端是在
 * bean 创建期握手的，脚本路径不存在就必然建不起来。压开关比让 bean 抛异常更干净，
 * 后者虽然也会被 McpSearchConfig 捕获降级，但日志里会多一条无意义的失败记录。
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

        // 开发态：-D 参数只有 Electron 会传，mvn spring-boot:run 不会。
        // 只要项目里已经 npm run runtime 预置过 open-websearch，就直接认领它，
        // 免得本地跑起来永远没有联网搜索（表现是模型回一句「联网工具不可用」）。
        Path bundled = detectBundledScript();
        if (bundled != null) {
            Map<String, Object> detected = new HashMap<>();
            detected.put(SCRIPT_KEY, bundled.toString());
            environment.getPropertySources().addFirst(new MapPropertySource("mcpSearchGuardDetected", detected));
            System.err.println("[mcp] 未指定 " + SCRIPT_KEY + "，自动使用项目内置运行时：" + bundled);
            return;
        }

        Map<String, Object> override = new HashMap<>();
        // 压自己的开关，而不是 spring.ai.mcp.client.enabled：装配权已经交给 McpSearchConfig，
        // 那边用 @ConditionalOnProperty(insightlens.mcp.enabled=true) 决定要不要建客户端。
        override.put(ENABLED_KEY, "false");
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

    /**
     * 在项目目录里找 prepare-runtime.mjs 预置好的入口。
     *
     * <p>从 {@code user.dir} 往上找三级：{@code mvn spring-boot:run} 的 user.dir 是项目根，
     * 从 frontend 目录或 IDE 子模块目录启动时也能命中。找不到返回 null，
     * 由调用方降级为「无联网搜索」。
     */
    private Path detectBundledScript() {
        String userDir = System.getProperty("user.dir", "");
        if (userDir.isBlank()) {
            return null;
        }
        String[] relatives = {
                "frontend/runtime/mcp/node_modules/open-websearch/build/index.js",
                "runtime/mcp/node_modules/open-websearch/build/index.js"
        };
        Path base;
        try {
            base = Path.of(userDir);
        } catch (InvalidPathException e) {
            return null;
        }
        for (int depth = 0; depth < 3 && base != null; depth++) {
            for (String relative : relatives) {
                Path candidate = base.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
            base = base.getParent();
        }
        return null;
    }

    @Override
    public int getOrder() {
        // 必须晚于 ConfigDataEnvironmentPostProcessor，否则读不到 application.yaml 里的值
        return Ordered.LOWEST_PRECEDENCE;
    }
}
