package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service(NodeName.JUDGE)
public class JudgeNode implements Node {
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public JudgeNode(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        log.info("[judge] >>> rowCount={}, 已有 verdict={}, sql={}",
                state.getRowCount(), state.getVerdict(), state.getSql());
        if (state.getVerdict() != null) {
            log.info("[judge] 已有判定 {}，跳过模型调用", state.getVerdict());
            return;
        }

        String systemMsg = buildSystemMsg("message/JudgeNode.txt");
        String userMsg = buildUserMag(state);
        List<Message> messages = List.of(
                new SystemMessage(systemMsg),
                new UserMessage(userMsg)
        );
        String raw = llmClient.call(messages);
        log.debug("[judge] 模型返回原文：{}", raw);
        try {
            JudgeEntity entity = objectMapper.readValue(extractJson(raw), JudgeEntity.class);
            String verdict = entity.verdict().trim();
            state.setVerdict(verdict);
            if ("off_target".equals(verdict)) {
                // reason 是给 sql_generate 的可操作修正指令，不回复用户
                state.setError(entity.reason());
                state.setAnswer(null);
                log.warn("[judge] <<< off_target，进入修正循环：reason={}", entity.reason());
            } else if ("empty".equals(verdict)) {
                // reason 是面向用户的场景化说明，直接作为最终回答
                state.setError(null);
                state.setAnswer(entity.reason());
                log.info("[judge] <<< empty，如实回答：answer={}", entity.reason());
            } else {
                state.setError(null);
                state.setAnswer(entity.answer());
                log.info("[judge] <<< ok，cost={}ms, answer={}",
                        System.currentTimeMillis() - start, entity.answer());
            }
        } catch (Exception e) {
            log.error("[judge] JSON 解析失败，raw={}", raw, e);
            throw new RuntimeException("JudgeNode：JSON 解析失败，原文：" + raw, e);
        }
    }

    /**
     * 只截取模型返回中的 JSON 本体，兼容 ```json 围栏和前后多余话术。
     * 围栏位于花括号之外，substring 会一并切掉；找不到完整 JSON 时抛带原文的异常。
     */
    private String extractJson(String raw) {
        if (raw == null) {
            throw new RuntimeException("JudgeNode：模型返回为 null");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new RuntimeException("JudgeNode：模型返回中未找到 JSON，原文：" + raw);
        }
        return raw.substring(start, end + 1);
    }

    @SuppressWarnings({"all"})
    private String buildUserMag(AnalysisState state) {
        List<Object> rows = state.getResultRows();
        StringBuilder builder = new StringBuilder();
        builder.append("用户澄清后的问题：").append(state.getClarifiedQuestion()).append("\n");
        builder.append("口径假设：").append(state.getAssumptions() == null ? "[]" : Arrays.toString(state.getAssumptions())).append("\n");
        builder.append("生成的sql：").append(state.getSql()).append("\n");
        builder.append("执行结果：").append(rows.toString()).append("rowCount=").append(state.getRowCount()).append("\n");
        return builder.toString();
    }

    @SuppressWarnings({"all"})
    private String buildSystemMsg(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[judge] 提示词读取失败 path={}", path, e);
            throw new RuntimeException(e);
        }
    }
}

record JudgeEntity(

        /** 判定结论：ok / off_target / empty */
        @JsonProperty("verdict")
        String verdict,

        /** 双语义：off_target 时是 SQL 修正指令，empty 时是面向用户的场景化说明 */
        @JsonProperty("reason")
        String reason,

        @JsonProperty("answer")
        String answer
) {}
