package com.gdou.agent.infra;

import com.gdou.agent.infra.constant.CodeConstant;
import com.gdou.agent.api.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
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
import java.util.List;

/**
 * 模型 API Key 的运行时管理。
 *
 * 为什么不在 Controller 里直接改配置文件：Key 有两处状态——磁盘上的
 * application-local.yaml（重启后仍生效）和运行中的 OpenAiChatModel（当前进程实际使用），
 * 二者必须同时更新，否则用户会看到「保存成功但问数还是 401」。
 *
 * 做法：以启动时自动装配的 ChatModel 为模板，每次用 mutate() 换掉 apiKey 生成新实例，
 * 原子替换 volatile 引用。调用方拿到的始终是完整构建过的 OpenAiChatModel，
 * 工具调用、重试、observation 配置都不会丢，也不需要重启后端。
 */
@Slf4j
@Service
public class ApiKeyService {

    /** 只回显头尾各 4 位，既不泄露完整密钥，也方便用户确认是这一把 Key。 */
    public record ApiKeyStatus(String masked, String baseUrl, boolean configured) {
    }

    private record Level(int indent, String name) {
    }

    private final OpenAiApi openAiApi;
    private final OpenAiChatModel templateChatModel;
    private final Path configPath;
    private final String baseUrl;

    private volatile String apiKey;
    private volatile ChatModel currentChatModel;

    public ApiKeyService(OpenAiApi openAiApi, OpenAiChatModel templateChatModel, Environment env) {
        this.openAiApi = openAiApi;
        this.templateChatModel = templateChatModel;
        // Electron 由 -Dinsightlens.home 指到 userData，普通开发模式退回项目根目录，
        // 与 application.yaml 里 spring.config.import 的定位规则保持一致。
        String home = System.getProperty("insightlens.home", System.getProperty("user.dir"));
        this.configPath = Path.of(home, "application-local.yaml");
        this.baseUrl = env.getProperty("spring.ai.openai.base-url", "");
        this.apiKey = env.getProperty("spring.ai.openai.api-key", "");
        this.currentChatModel = templateChatModel;
    }

    /** 所有需要调模型的地方都从这里取，保证改 Key 后下一轮请求立刻用新 Key。 */
    public ChatModel chatModel() {
        return currentChatModel;
    }

    public synchronized ApiKeyStatus status() {
        return new ApiKeyStatus(mask(apiKey), baseUrl, apiKey != null && !apiKey.isBlank());
    }

    /**
     * 校验 → 落盘 → 换运行时模型。落盘失败时不换内存，避免「这次能用、重启就失效」。
     */
    public synchronized ApiKeyStatus update(String newKey) {
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

        try {
            writeConfig(trimmed);
        } catch (IOException e) {
            log.error("[api-key] 写入配置失败 path={}", configPath, e);
            throw new BusinessException(CodeConstant.CALL_ERROR,
                    "保存失败：无法写入 " + configPath + "（请检查文件权限）");
        }

        try {
            OpenAiApi api = openAiApi.mutate().apiKey(trimmed).build();
            currentChatModel = templateChatModel.mutate().openAiApi(api).build();
        } catch (RuntimeException e) {
            // 配置已经写好了，重建失败时至少保证重启后能生效；把原因明确告诉用户。
            log.error("[api-key] 热替换模型客户端失败，配置已保存，重启后可生效", e);
            throw new BusinessException(CodeConstant.CALL_ERROR, "配置已保存，但热更新失败，请重启应用");
        }

        apiKey = trimmed;
        log.info("[api-key] 已更新 API Key（{}），配置={}，即时生效", mask(trimmed), configPath);
        return status();
    }

    /** 先写临时文件再原子替换，避免中途断电把密钥文件写成半截。 */
    private void writeConfig(String value) throws IOException {
        Path dir = configPath.getParent();
        if (dir != null) Files.createDirectories(dir);
        String old = Files.exists(configPath)
                ? Files.readString(configPath, StandardCharsets.UTF_8)
                : "";
        String updated = replaceOpenAiApiKey(old, value);
        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(tmp, updated, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 只替换 spring → ai → openai → api-key 这一行，注释和其他密钥（如 tavily.api-key）原样保留。
     * 用缩进层级判断归属，而不是全文替换 api-key，避免误改别的厂商配置。
     */
    static String replaceOpenAiApiKey(String yaml, String value) {
        String newline = yaml.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\\R", -1)));
        Deque<Level> stack = new ArrayDeque<>();
        boolean replaced = false;
        int openaiIndex = -1;
        int aiIndex = -1;
        int springIndex = -1;
        int openaiIndent = 0;
        int aiIndent = 0;
        int springIndent = 0;

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

            if ("spring".equals(full) && springIndex < 0) {
                springIndex = i;
                springIndent = indent;
            }
            if ("spring.ai".equals(full) && aiIndex < 0) {
                aiIndex = i;
                aiIndent = indent;
            }
            if ("spring.ai.openai".equals(full) && openaiIndex < 0) {
                openaiIndex = i;
                openaiIndent = indent;
            }
            if ("api-key".equals(name) && "spring.ai.openai".equals(parent)) {
                lines.set(i, indentSpaces(indent) + "api-key: " + yamlQuote(value));
                replaced = true;
                continue; // 标量行不入栈
            }
            if (rest.isEmpty() || "{}".equals(rest)) stack.push(new Level(indent, name));
        }

        if (!replaced) {
            String keyLine = yamlQuote(value);
            if (openaiIndex >= 0) {
                lines.add(openaiIndex + 1, indentSpaces(openaiIndent + 2) + "api-key: " + keyLine);
            } else if (aiIndex >= 0) {
                int at = aiIndex + 1;
                lines.add(at++, indentSpaces(aiIndent + 2) + "openai:");
                lines.add(at, indentSpaces(aiIndent + 4) + "api-key: " + keyLine);
            } else if (springIndex >= 0) {
                int at = springIndex + 1;
                lines.add(at++, indentSpaces(springIndent + 2) + "ai:");
                lines.add(at++, indentSpaces(springIndent + 4) + "openai:");
                lines.add(at, indentSpaces(springIndent + 6) + "api-key: " + keyLine);
            } else {
                // 空文件（或只有空白行）时不要留下多余空行
                boolean blank = lines.stream().allMatch(String::isBlank);
                if (blank) lines.clear();
                else if (!lines.get(lines.size() - 1).isEmpty()) lines.add("");
                lines.add("spring:");
                lines.add("  ai:");
                lines.add("    openai:");
                lines.add("      api-key: " + keyLine);
            }
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

    static String mask(String key) {
        if (key == null || key.isBlank()) return "（未配置）";
        int n = key.length();
        if (n <= 8) return key.charAt(0) + "****";
        return key.substring(0, 4) + "****" + key.substring(n - 4);
    }
}
