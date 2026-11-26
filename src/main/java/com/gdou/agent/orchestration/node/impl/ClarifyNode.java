package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 意图澄清状态节点执行
 */
@Slf4j
@Service(NodeName.CLARIFY)
public class ClarifyNode implements Node {
    @Autowired
    private LlmClient LlmClient;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 相对时间换算的基准日。生产留空走今天；评测时通过 -Dinsightlens.eval-base-date=2026-11-11
     * 注入固定值，与评测集 eval_date / gold_sql 对齐，避免日期漂移导致正确率虚低。
     */
    @Value("${insightlens.eval-base-date:}")
    private String evalBaseDate;

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        String question = state.getQuestion();
        // 相对时间需要锚点：把基准日期拼进 SystemMessage，不碰 userMessage（原始问题仍是第一行）
        String baseDate = (evalBaseDate == null || evalBaseDate.isBlank())
                ? LocalDate.now().toString() : evalBaseDate.trim();
        String systemMessage = prompt("message/ClarifyNode.txt")
                + "\n\n## 当前日期\n" + baseDate
                + "\n问题里的「今天 / 上周 / 近 30 天 / 今年」等相对时间，一律以该日期为基准换算成具体起止日期，"
                + "并写进 clarified_question 与 assumptions 两处。";
        // 口径词典只存在库表注释里，澄清阶段看不到它就会自行猜指标算法并写进 assumptions，
        // 生成阶段再忠实执行错误假设。这里把词典注入 systemMessage 并声明其优先级高于推断。
        String glossary = businessGlossary();
        if (!glossary.isEmpty()) {
            systemMessage += "\n\n## 库内口径词典（最高优先级，先读再补全）\n" + glossary
                    + "\n\n使用规则：\n"
                    + "- 词典已定义的指标算法、分母范围、状态枚举、时间字段与时间边界必须逐字沿用，"
                    + "不得自行改写，也不得就这些项向用户追问；把实际采用的定义逐条写进 assumptions。\n"
                    + "- 词典未定义的项才由你推断；相对时间换算结果若与词典边界不一致，以词典为准。";
        }
        String tables = tableList();
        // 标题与 ClarifyNode.txt 的「1. 输入」逐字对齐，第三列是行数不是列定义
        String userMessage = question + "\n\n候选表清单（文件名 | sheet 名 | 行数）：\n" + tables;
        List<Message> messages = List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
        log.info("[clarify] >>> baseDate={}, glossaryChars={}, question={}, categoryId={}, 候选表行数={}",
                baseDate, glossary.length(), question, SessionHolder.getCategory(), tables.lines().count());

        String raw = LlmClient.call(messages);
        log.debug("[clarify] 模型返回原文：{}", raw);
        ClarifyEntity node = parseJson(raw);
        BeanUtils.copyProperties(node, state);
        // 选表已废弃：候选表由 schema_retrieve 按当前分类从 file_view 全量收集，澄清阶段不再输出 selected_tables
        long end = System.currentTimeMillis();
        log.info("[clarify] <<< cost={}ms, needClarify={}, clarifiedQuestion={}, assumptions={}, questionToUser={}",
                end - start, state.getNeedClarify(), state.getClarifiedQuestion(),
                Arrays.toString(state.getAssumptions()), state.getQuestionToUser());
    }

    /**
     * 读取业务表的表注释（口径词典写在 COMMENT ON TABLE 里）注入澄清阶段。
     * 注入全部业务表；系统表注释对业务口径无帮助，排除。
     * 换行压成空格，避免把一条定义拆成多行稀释提示词。
     */
    private String businessGlossary() {
        String sql = "select '-- ' || table_name || '：' || replace(comment, chr(10), ' ')"
                + " from duckdb_tables()"
                + " where schema_name = 'main' and comment is not null and comment <> ''"
                + " and lower(table_name) not in ('session','message','category','file_view',"
                + "'evaluation_run','eval_case_result','trace_span')"
                + " order by table_name";
        List<String> lines = jdbc.queryForList(sql, String.class);
        return lines == null || lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /**
     * 从 file_view 拼候选表清单，一行一个 sheet：orig_name | sheet_name | row_count。
     * 按本窗口绑定的 category_id 过滤实现大分类隔离；categoryId 为空时不加过滤（窗口未绑定分类）。
     * 这里只做候选呈现，真实视图名由 schema_retrieve 阶段从 file_view 取；建 VIEW 由上传链路负责。
     */
    private String tableList() {
        Integer categoryId = SessionHolder.getCategory();
        String base = "select orig_name, sheet_name, row_count from file_view"
                + " where status = 'success'";
        List<Map<String, Object>> rows = categoryId == null
                ? jdbc.queryForList(base + " order by orig_name")
                : jdbc.queryForList(base + " and category_id = ? order by orig_name", categoryId);
        if (rows.isEmpty()) {
            log.warn("[clarify] file_view 无可用记录 categoryId={}，候选表清单无表可选", categoryId);
            return "（库中暂无任何数据表）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append(row.get("orig_name"))
                    .append(" | ").append(row.get("sheet_name"))
                    .append(" | ").append(row.get("row_count"))
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
@JsonIgnoreProperties(ignoreUnknown = true)
record ClarifyEntity (

    @JsonProperty("clarified_question")
    String clarifiedQuestion,

    @JsonProperty("assumptions")
    String[] assumptions,

    @JsonProperty("need_clarify")
    Boolean needClarify,

    @JsonProperty("question_to_user")
    String questionToUser

){}
