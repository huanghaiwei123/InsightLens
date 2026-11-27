package com.gdou.agent.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 联网搜索（open-websearch，stdio）客户端的自建装配。
 *
 * <p>为什么不用 Spring AI 自带的 {@code McpToolCallbackAutoConfiguration}：
 * 它在上下文刷新时同步 initialize，而 initialize 的超时用的是 MCP SDK 的
 * {@code initializationTimeout}，<b>默认 20 秒</b>。Spring AI 1.1.2 的
 * {@code McpClientCommonProperties} 只暴露了 {@code requestTimeout}，
 * 没有暴露这一项，所以配置文件里写多大都没用。
 *
 * <p>open-websearch 有 6000 多个依赖文件，页缓存冷的时候首次加载实测能超过 20 秒，
 * 于是初始化直接抛 {@code TimeoutException}，整个 ApplicationContext 创建失败 ——
 * 表现就是「把开关改成 true 之后启动超时」。Electron 靠起服务前预热躲开了这一击，
 * 但 IDEA 直接 Run、{@code mvn spring-boot:run} 都没有预热，必踩。
 *
 * <p>这里改成自己 {@code McpClient.sync(...)} 装配，把 initialize 超时放宽到
 * {@link #INIT_TIMEOUT}，并且在初始化失败时<b>不抛出</b>，返回一个空工具集：
 * 应用照常启动，联网能力本轮不可用，模型会按「工具故障」措辞据实说明。
 *
 * <p>开关仍是 {@code insightlens.mcp.enabled}，与 Electron 的约定不变。
 * 由于本类接管了装配，{@code spring.ai.mcp.client.enabled} 在主配置里恒为 false，
 * 避免两套客户端同时握手。
 */
@Configuration
@ConditionalOnProperty(name = "insightlens.mcp.enabled", havingValue = "true")
@Slf4j
public class McpSearchConfig {

    /** initialize 握手超时：冷启动实测 16–25 秒，给到 60 秒留足余量 */
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(60);

    /** 工具调用超时：搜索要抓网页，明显长于握手 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public ToolCallbackProvider mcpSearchToolCallbacks(Environment env) {
        String node = env.getProperty("insightlens.mcp.node", "node");
        String script = env.getProperty("insightlens.mcp.websearch-script", "");
        long start = System.currentTimeMillis();
        try {
            McpSyncClient client = createClient(node, script);
            List<String> tools = client.listTools().tools().stream()
                    .map(t -> t.name())
                    .toList();
            log.info("[mcp] 联网搜索已就绪 cost={}ms, tools={}", System.currentTimeMillis() - start, tools);
            return new CloseableToolProvider(new SyncMcpToolCallbackProvider(client), client);        } catch (Exception e) {
            // 不抛：MCP 是增强能力，起不来不该让整个应用起不来。
            // 上层拿到空工具集，会按「开了但没挂上」的措辞告诉模型和用户。
            log.warn("[mcp] 联网搜索初始化失败，本次降级为纯本地查数 cost={}ms, node={}, script={}, cause={}",
                    System.currentTimeMillis() - start, node, script, e.toString());
            return () -> new ToolCallback[0];
        }
    }

    private McpSyncClient createClient(String node, String script) {
        ServerParameters params = ServerParameters.builder(node)
                .args(script)
                .addEnvVar("MODE", "stdio")
                // 中文内容为主的场景用百度，比 bing 稳（bing 反爬最勤）
                .addEnvVar("DEFAULT_SEARCH_ENGINE", "baidu")
                .addEnvVar("ALLOWED_SEARCH_ENGINES", "baidu,sogou,bing,csdn,juejin,duckduckgo")
                .build();
        StdioClientTransport transport =
                new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
        return McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INIT_TIMEOUT)
                .build();
    }

    /**
     * stdio 客户端下面挂着 node 子进程，必须显式关掉，否则 IDE 里 Stop 之后
     * 子进程会残留（Electron 侧用 taskkill /T 兜了一层，IDE 没有）。
     */
    private static final class CloseableToolProvider implements ToolCallbackProvider, DisposableBean {

        private final ToolCallbackProvider delegate;
        private final McpSyncClient client;

        private CloseableToolProvider(ToolCallbackProvider delegate, McpSyncClient client) {
            this.delegate = delegate;
            this.client = client;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            try {
                return filterSearchOnly(delegate.getToolCallbacks());
            } catch (Exception e) {
                // 运行期工具列表拉不到（子进程被杀等）：本轮当作没有联网工具，别让对话直接崩
                log.warn("[mcp] 读取工具列表失败，本轮按无联网工具处理: {}", e.toString());
                return new ToolCallback[0];
            }
        }

        /**
         * 只保留搜索工具。
         *
         * <p>open-websearch 默认暴露 search + fetchCsdnArticle / fetchGithubReadme /
         * fetchLinuxDoArticle / fetchWebContent 等抓取工具。查数场景只用到 search，
         * 多出来的工具会让模型倾向于一次并发调用多个，而第三方 OpenAI 兼容中转在
         * 流式 tool_call 分片不规范时会撞上 Spring AI 的
         * {@code OpenAiStreamFunctionCallingHelper.merge} 空指针
         * （{@code current.name()} 前没有判空，1.1.8 仍未修）。
         * 少暴露一个工具，就少一次触发机会。
         */
        private ToolCallback[] filterSearchOnly(ToolCallback[] all) {
            ToolCallback[] picked = Arrays.stream(all)
                    .filter(cb -> {
                        String name = cb.getToolDefinition().name().toLowerCase(Locale.ROOT);
                        return name.equals("search") || name.endsWith("_search") || name.endsWith("search");
                    })
                    .toArray(ToolCallback[]::new);
            if (picked.length == 0) {
                log.warn("[mcp] 工具清单里没有可用的搜索工具，实际工具={}",
                        Arrays.stream(all).map(cb -> cb.getToolDefinition().name()).toList());
            }
            return picked;
        }

        @Override
        public void destroy() {
            try {
                client.closeGracefully();
                log.info("[mcp] 联网搜索客户端已关闭");
            } catch (Exception e) {
                log.warn("[mcp] 关闭联网搜索客户端失败: {}", e.toString());
            }
        }
    }
}
