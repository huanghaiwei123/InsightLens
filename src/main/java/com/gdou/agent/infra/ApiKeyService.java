package com.gdou.agent.infra;

import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.infra.constant.CodeConstant;
import com.gdou.agent.infra.llm.LlmProvider;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主模型与 API Key 的运行时管理。
 *
 * 为什么不在 Controller 里直接改配置文件：Key 与「选哪个厂商」都有两处状态 ——
 * 磁盘上的 application-local.yaml（重启后仍生效）和进程内的模型实例（当前实际使用），
 * 二者必须同时更新，否则用户会看到「保存成功但问数还是 401」。
 *
 * 为什么 OpenAI 与 DeepSeek 的构建方式不同：
 * - OpenAI 走 starter 自动装配，OpenAiChatModel 自带 mutate()，换 key 只换一个 api 对象；
 * - DeepSeekChatModel 在 Spring AI 1.1.2 里没有 mutate()，只能 DeepSeekApi.builder() +
 *   DeepSeekChatModel.builder() 整实例重建。因此这里用「懒构建」：只在真正要用的时候建一次，
 *   切换厂商或改 key 时把实例置空，下一轮请求再重建 —— 启动阶段不做任何模型构建。
 *
 * 为什么不启用 DeepSeek 的自动装配：它的 deepSeekChatModel() 里有
 * Assert.hasText(apiKey, "DeepSeek API key must be set")，key 为空会让整个
 * ApplicationContext 起不来。而产品要求「用户自己填 key、自己选模型」，
 * 首次安装时两个厂商都可能没有 key，所以主配置用 spring.ai.model.chat=openai
 * 只保留 OpenAI 的自动装配，DeepSeek 由这里手动构建，没 key 就不建、也不影响启动。
 */
@Slf4j
@Service
public class ApiKeyService {

    /** 老接口的视图：只描述 OpenAI 一家，保留给 /settings/api-key 与已有前端调用。 */
    public record ApiKeyStatus(String masked, String baseUrl, boolean configured) {
    }

    /** 设置页需要的一行：某一家的模型、地址、掩码后的 key 与是否当前生效。 */
    public record ProviderStatus(String id, String label, String model, String baseUrl,
                                 String masked, boolean configured, boolean active) {
    }

    /** 设置页整体视图：active 是当前主模型，providers 固定按枚举顺序返回。 */
    public record ModelSettings(String active, List<ProviderStatus> providers) {
    }

    /** 写入 application-local.yaml 的「当前主模型」键，重启后据此恢复。 */
    static final String PROVIDER_PATH = "insightlens.llm.provider";

    private record Level(int indent, String name) {
    }

    private final OpenAiApi openAiApi;
    private final OpenAiChatModel templateChatModel;
    private final Path configPath;

    /** 可选依赖：拿不到就用 DeepSeekChatModel 的默认值，不至于因为少一个 bean 起不来。 */
    private final ObjectProvider<ToolCallingManager> toolCallingManager;
    private final ObjectProvider<RetryTemplate> retryTemplate;
    private final ObjectProvider<ObservationRegistry> observationRegistry;

    /** provider id → 明文 key。只在进程内使用，接口一律只回掩码。 */
    private final Map<String, String> keys = new ConcurrentHashMap<>();

    private final Map<String, String> baseUrls = new ConcurrentHashMap<>();
    private final Map<String, String> modelNames = new ConcurrentHashMap<>();

    private volatile String activeProvider;

    /** 当前主模型的实例；null 表示「还没建 / 需要重建」，由 chatModel() 懒构建。 */
    private volatile ChatModel activeModel;

    public ApiKeyService(OpenAiApi openAiApi,
                         OpenAiChatModel templateChatModel,
                         Environment env,
                         ObjectProvider<ToolCallingManager> toolCallingManager,
                         ObjectProvider<RetryTemplate> retryTemplate,
                         ObjectProvider<ObservationRegistry> observationRegistry) {
        this.openAiApi = openAiApi;
        this.templateChatModel = templateChatModel;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;

        // Electron 由 -Dinsightlens.home 指到 userData，普通开发模式退回项目根目录，
        // 与 application.yaml 里 spring.config.import 的定位规则保持一致。
        String home = System.getProperty("insightlens.home", System.getProperty("user.dir"));
        this.configPath = Path.of(home, "application-local.yaml");

        for (LlmProvider provider : LlmProvider.values()) {
            String key = readProperty(env, provider.apiKeyPath(), "");
            if (hasText(key)) keys.put(provider.id(), key);
            baseUrls.put(provider.id(), readProperty(env, provider.baseUrlPath(), defaultBaseUrl(provider)));
            modelNames.put(provider.id(), readProperty(env, provider.modelPath(), defaultModel(provider)));
        }

        String configured = readProperty(env, PROVIDER_PATH, LlmProvider.OPENAI.id());
        this.activeProvider = LlmProvider.of(configured).id();
        log.info("[model] 启动配置：主模型={}，OpenAI={}，DeepSeek={}", activeProvider,
                configured(LlmProvider.OPENAI) ? "已配置 Key" : "未配置 Key",
                configured(LlmProvider.DEEPSEEK) ? "已配置 Key" : "未配置 Key");
    }

    /**
     * 读配置的兜底。
     *
     * Spring Boot 对「配置属性」走 strict 占位符解析：只要值里引用了未定义的环境变量
     * （例如从别处抄来的 api-key: ${DEEPSEEK_API_KEY}），ConfigurationPropertySourcesPropertyResolver
     * 会直接抛 PlaceholderResolutionException，整个 ApplicationContext 建不起来 ——
     * 而这里只是读一个「用户可能还没填」的 key，不该有这种杀伤力。
     * 解析失败一律按未配置处理，并在启动日志里留一条明确的 WARN。
     */
    private static String readProperty(Environment env, String key, String fallback) {
        try {
            String value = env.getProperty(key);
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            log.warn("[model] 配置 {} 无法解析（{}），按未配置处理；请在设置页直接填写 Key",
                    key, e.getMessage());
            return fallback;
        }
    }

    private static String defaultBaseUrl(LlmProvider provider) {
        return provider == LlmProvider.DEEPSEEK ? "https://api.deepseek.com" : "";
    }

    private static String defaultModel(LlmProvider provider) {
        return provider == LlmProvider.DEEPSEEK ? "deepseek-chat" : "gpt-4o-mini";
    }

    // ------------------------------------------------------------------
    // 对上层暴露的唯一出口：七节点与 ReactAgent 只认这一个方法
    // ------------------------------------------------------------------

    /**
     * 当前主模型。没配 key 时抛业务异常而不是返回一个必然 401 的实例 ——
     * 让用户看到「去设置页配置」，而不是一条无从下手的调用失败。
     */
    public ChatModel chatModel() {
        ChatModel model = activeModel;
        if (model != null) return model;
        synchronized (this) {
            if (activeModel != null) return activeModel;
            LlmProvider provider = LlmProvider.of(activeProvider);
            String key = keys.get(provider.id());
            if (!hasText(key)) {
                throw new BusinessException(CodeConstant.CALL_ERROR,
                        provider.label() + " 还没有配置 API Key，请到设置页填写后再提问");
            }
            activeModel = build(provider, key);
            log.info("[model] 已构建主模型 {}（{}）", activeProvider, modelNames.get(provider.id()));
            return activeModel;
        }
    }

    // ------------------------------------------------------------------
    // 设置页读取 / 切换 / 改 key
    // ------------------------------------------------------------------

    /** 两家一起返回，前端一次渲染完，不用按 provider 发多次请求。 */
    public ModelSettings settings() {
        List<ProviderStatus> providers = new ArrayList<>();
        for (LlmProvider provider : LlmProvider.values()) {
            String key = keys.get(provider.id());
            providers.add(new ProviderStatus(
                    provider.id(),
                    provider.label(),
                    modelNames.get(provider.id()),
                    baseUrls.get(provider.id()),
                    mask(key),
                    hasText(key),
                    provider.id().equals(activeProvider)));
        }
        return new ModelSettings(activeProvider, providers);
    }

    /**
     * 切换主模型：先落盘再换内存。落盘失败时不切，避免「这次能用、重启又变回去」。
     * 没配 key 的厂商不允许切过去，否则下一轮请求才报错，用户会以为是切换失效。
     */
    public synchronized ModelSettings switchProvider(String id) {
        LlmProvider provider = LlmProvider.of(id);
        if (!hasText(keys.get(provider.id()))) {
            throw new BusinessException(CodeConstant.CALL_ERROR,
                    provider.label() + " 还没有配置 API Key，请先在下方填写并保存");
        }
        writeConfig(PROVIDER_PATH, provider.id());
        this.activeProvider = provider.id();
        this.activeModel = null;
        log.info("[model] 主模型已切换为 {}（{}）", provider.id(), modelNames.get(provider.id()));
        return settings();
    }

    /**
     * 更新某一家的 Key：落盘 → 换内存里的 key → 若它正是主模型则作废实例，下一轮重建。
     *
     * 额外一条：如果当前主模型根本没有 key（全新安装），而这是第一把可用的 key，
     * 就顺手切到它 —— 否则用户填完 key 还是不能问数，会以为保存没生效。
     */
    public synchronized ModelSettings updateKey(String id, String newKey) {
        LlmProvider provider = LlmProvider.of(id);
        String trimmed = validateKey(newKey);

        writeConfig(provider.apiKeyPath(), trimmed);
        keys.put(provider.id(), trimmed);
        if (provider.id().equals(activeProvider)) {
            activeModel = null;
        }

        if (!configured(LlmProvider.of(activeProvider))) {
            activeProvider = provider.id();
            activeModel = null;
            writeConfig(PROVIDER_PATH, provider.id());
            log.info("[model] 原主模型未配置 Key，自动改用它：{}", provider.id());
        }

        log.info("[model] 已更新 {} 的 API Key（{}），配置={}", provider.id(), mask(trimmed), configPath);
        return settings();
    }

    // ------------------------------------------------------------------
    // 老接口兼容：/settings/api-key 只认 OpenAI
    // ------------------------------------------------------------------

    public ApiKeyStatus status() {
        String key = keys.get(LlmProvider.OPENAI.id());
        return new ApiKeyStatus(mask(key), baseUrls.get(LlmProvider.OPENAI.id()), hasText(key));
    }

    /** 保留原语义：只改 OpenAI 的 key，并返回 OpenAI 的视图。设置页已改用 updateKey。 */
    public synchronized ApiKeyStatus update(String newKey) {
        updateKey(LlmProvider.OPENAI.id(), newKey);
        return status();
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    private ChatModel build(LlmProvider provider, String key) {
        if (provider == LlmProvider.OPENAI) {
            // 模板实例是启动时自动装配好的，保留了工具调用、重试与 observation 配置，
            // 只把 api 换成新 key，其余不动。
            OpenAiApi api = openAiApi.mutate().apiKey(key).build();
            return templateChatModel.mutate().openAiApi(api).build();
        }

        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(key)
                .baseUrl(baseUrls.get(provider.id()))
                .build();
        DeepSeekChatModel.Builder builder = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(modelNames.get(provider.id()))
                        // 与 OpenAI 侧保持一致：问数要可复现，不要发散
                        .temperature(0.0)
                        .build());
        // 容器里有这些 bean 就沿用，保证工具调用行为与自动装配一致；
        // 没有时 builder 会用 DeepSeekChatModel 自带的默认实现。
        toolCallingManager.ifAvailable(builder::toolCallingManager);
        retryTemplate.ifAvailable(builder::retryTemplate);
        observationRegistry.ifAvailable(builder::observationRegistry);
        return builder.build();
    }

    private boolean configured(LlmProvider provider) {
        return hasText(keys.get(provider.id()));
    }

    private String validateKey(String newKey) {
        if (newKey == null || newKey.isBlank()) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "API Key 不能为空");
        }
        String trimmed = newKey.trim();
        if (trimmed.length() < 8) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "API Key 太短，请检查是否粘贴完整");
        }
        if (trimmed.contains("\n") || trimmed.contains("\r") || trimmed.contains("'")) {
            throw new BusinessException(CodeConstant.CALL_ERROR, "API Key 不能包含换行或单引号");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // 配置文件读写
    // ------------------------------------------------------------------

    /** 先写临时文件再原子替换，避免中途断电把密钥文件写成半截。 */
    private void writeConfig(String path, String value) {
        try {
            writeConfigOrThrow(path, value);
        } catch (IOException e) {
            log.error("[model] 写入配置失败 path={}, key={}", configPath, path, e);
            throw new BusinessException(CodeConstant.CALL_ERROR,
                    "保存失败：无法写入 " + configPath + "（请检查文件权限）");
        }
    }

    private void writeConfigOrThrow(String path, String value) throws IOException {
        Path dir = configPath.getParent();
        if (dir != null) Files.createDirectories(dir);
        String old = Files.exists(configPath)
                ? Files.readString(configPath, StandardCharsets.UTF_8)
                : "";
        String updated = setProperty(old, path, value);
        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(tmp, updated, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 老测试与老调用方仍在用这个名字，语义收窄为「改 spring.ai.openai.api-key」。 */
    static String replaceOpenAiApiKey(String yaml, String value) {
        return setProperty(yaml, LlmProvider.OPENAI.apiKeyPath(), value);
    }

    /**
     * 在 YAML 里按「点分路径」写入一个标量，尽量保留注释与其它厂商配置。
     *
     * 三种情况：
     * 1. 键已存在 → 原地只改这一行（保留原缩进）；
     * 2. 父节点存在但键缺失 → 插在父节点第一行之后；
     * 3. 父节点也不存在 → 从最深的已存在祖先往下逐级补出来，空文件则生成整条链。
     *
     * 这里刻意不引 SnakeYAML 做 parse→dump：dump 会丢掉注释和顺序，
     * 而这个文件是用户会手改的，注释（哪把 key 从哪来）本身就是资产。
     */
    static String setProperty(String yaml, String path, String value) {
        String newline = yaml.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\\R", -1)));
        String[] segments = path.split("\\.");

        // 一遍扫描同时完成两件事：命中目标键就替换；否则记下所有对象节点的位置备用。
        Map<String, int[]> objects = new HashMap<>();
        Deque<Level> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            while (!stack.isEmpty() && stack.peek().indent() >= indent) stack.pop();

            int colon = stripped.indexOf(':');
            if (colon < 0) continue;
            String name = stripped.substring(0, colon).trim();
            String rest = stripped.substring(colon + 1).trim();
            String parent = path(stack);
            String full = parent.isEmpty() ? name : parent + "." + name;

            if (full.equals(path)) {
                lines.set(i, indentSpaces(indent) + name + ": " + yamlQuote(value));
                return String.join(newline, lines);
            }
            if (rest.isEmpty() || "{}".equals(rest)) {
                objects.putIfAbsent(full, new int[]{i, indent});
                stack.push(new Level(indent, name));
            }
        }

        // 键不存在：从最深的祖先开始找插入点
        for (int depth = segments.length - 1; depth >= 1; depth--) {
            String ancestor = String.join(".", Arrays.copyOfRange(segments, 0, depth));
            int[] at = objects.get(ancestor);
            if (at == null) continue;

            int indent = at[1];
            List<String> added = new ArrayList<>();
            for (int i = depth; i < segments.length; i++) {
                indent += 2;
                added.add(indentSpaces(indent) + segments[i]
                        + (i == segments.length - 1 ? ": " + yamlQuote(value) : ":"));
            }
            lines.addAll(at[0] + 1, added);
            return String.join(newline, lines);
        }

        // 连 spring 都没有：整条链补出来。纯空白文件直接生成，
        // 非空文件追加到末尾 —— 插在开头会把用户写的注释头挤到配置后面。
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            chain.add(indentSpaces(i * 2) + segments[i]
                    + (i == segments.length - 1 ? ": " + yamlQuote(value) : ":"));
        }
        if (lines.stream().allMatch(String::isBlank)) {
            lines.clear();
            lines.addAll(chain);
        } else {
            if (!lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.addAll(chain);
        }
        return String.join(newline, lines);
    }

    private static String path(Deque<Level> stack) {
        // ArrayDeque.push 放在头部，迭代顺序是「内层 → 外层」，
        // 这里倒过来拼回 spring.ai.openai 这种从外到内的路径。
        List<String> names = new ArrayList<>();
        for (Level level : stack) names.add(0, level.name());
        return String.join(".", names);
    }

    private static String indentSpaces(int n) {
        return " ".repeat(Math.max(0, n));
    }

    private static String yamlQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String mask(String key) {
        if (key == null || key.isBlank()) return "（未配置）";
        int n = key.length();
        if (n <= 8) return key.charAt(0) + "****";
        return key.substring(0, 4) + "****" + key.substring(n - 4);
    }
}
