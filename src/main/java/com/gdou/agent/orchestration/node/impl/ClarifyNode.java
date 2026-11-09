package com.gdou.agent.orchestration.node.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.mapper.TableRegistryMapper;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.meta.entity.TableRegistry;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 意图澄清状态节点执行
 */
@Slf4j
@Service(NodeName.CLARIFY)
public class ClarifyNode implements Node {
    private final LlmClient LlmClient;
    private final ObjectMapper objectMapper;
    private final TableRegistryMapper tableRegistryMapper;

    public ClarifyNode(LlmClient LlmClient, ObjectMapper objectMapper, TableRegistryMapper tableRegistryMapper) {
        this.LlmClient = LlmClient;
        this.objectMapper = objectMapper;
        this.tableRegistryMapper = tableRegistryMapper;
    }

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        String question = state.getQuestion();
        String systemMessage = prompt("message/ClarifyNode.txt");
        String tables = tableList();
        // 标题与 ClarifyNode.txt 的「1. 输入」逐字对齐，第三列是结构指纹不是列定义
        String userMessage = question + "\n\n候选表清单（表名 | 语义标签 | 结构指纹）：\n" + tables;
        List<Message> messages = List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
        log.info("[clarify] >>> question={}, 候选表行数={}", question, tables.lines().count());

        String raw = LlmClient.call(messages);
        log.debug("[clarify] 模型返回原文：{}", raw);
        ClarifyEntity node = parseJson(raw);
        BeanUtils.copyProperties(node, state);
        // selectedTables 与 state 字段名不同，copyProperties 映射不到，显式赋值：
        // candidateTables 从此语义为「clarify 选中的表」，schema_retrieve 空时才兜底全量
        state.setCandidateTables(
                node.selectedTables() == null ? List.of() : List.of(node.selectedTables()));

        log.info("[clarify] <<< cost={}ms, needClarify={}, clarifiedQuestion={}, assumptions={}, selectedTables={}, questionToUser={}",
                System.currentTimeMillis() - start, state.getNeedClarify(), state.getClarifiedQuestion(),
                Arrays.toString(state.getAssumptions()), state.getCandidateTables(), state.getQuestionToUser());
    }

    /**
     * 从注册表拼候选表清单，一行一张表：表名 | 语义标签 | 结构指纹。
     * 结构指纹（schema_key）本身就携带列名与类型族，模型选表只看表名和标签即可，
     * 不需要读完整 DDL——DDL 留给 schema_retrieve 只取选中的表。
     */
    private String tableList() {
        LambdaQueryWrapper<TableRegistry> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(TableRegistry::getTableName, TableRegistry::getDataTag, TableRegistry::getSchemaKey);
        List<TableRegistry> tables = tableRegistryMapper.selectList(wrapper);
        if (tables.isEmpty()) {
            log.warn("[clarify] table_registry 为空，候选表清单无表可选");
            return "（库中暂无任何数据表）";
        }
        StringBuilder sb = new StringBuilder();
        for (TableRegistry t : tables) {
            sb.append(t.getTableName())
                    .append(" | ").append(t.getDataTag() == null ? "（无标签）" : t.getDataTag())
                    .append(" | ").append(t.getSchemaKey() == null ? "" : t.getSchemaKey())
                    .append('\n');
        }
        return sb.toString();
    }

    private ClarifyEntity parseJson(String raw) {
        String json = checkRaw(raw);
        try {
            return objectMapper.readValue(json, ClarifyEntity.class);
        } catch (JsonProcessingException e) {
            log.error("[clarify] JSON 解析失败，raw={}", raw, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 防止模型返回 json 时附带其余话术干扰 json 解析
     * @param raw
     * @return
     */
    private String checkRaw(String raw) {
        return raw.substring(raw.indexOf("{"), raw.lastIndexOf("}") + 1);
    }

    /**
     * 从指定路径中解析系统提示词
     * @param path
     * @return
     */
    private String prompt(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[clarify] 提示词读取失败 path={}", path, e);
            throw new RuntimeException(e);
        }
    }
}

/**
 * clarify 节点的 LLM 返回结构。
 * 提示词要求 snake_case，这里必须显式映射：
 * Jackson 默认按字段名精确匹配，不写 @JsonProperty 会静默得到全 null。
 */
record ClarifyEntity (

    @JsonProperty("clarified_question")
    String clarifiedQuestion,

    @JsonProperty("assumptions")
    String[] assumptions,

    @JsonProperty("need_clarify")
    Boolean needClarify,

    @JsonProperty("question_to_user")
    String questionToUser,

    /** 从候选表清单中选出的、与问题相关的表名；无相关表时为空数组 */
    @JsonProperty("selected_tables")
    String[] selectedTables

){}
