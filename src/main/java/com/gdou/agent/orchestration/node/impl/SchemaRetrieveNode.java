package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.constant.SchemaConstant;
import com.gdou.agent.infra.holder.SessionHolder;
import com.gdou.agent.infra.llm.LlmClient;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ② Schema 检索节点
 * 入：当前窗口的 categoryId（SessionHolder）、澄清后的问题
 * 出：fileCatalog（本分类可读 Excel 的路径 + sheet + 列结构）、allowedFilePaths（路径白名单）、
 *     tableSchemas（仅系统表 DDL + 表注释，供模型查元数据用）、
 *     dataSufficiency / dataLimitations / queryPlan / dataLimitationNote（数据可用性评估）
 *
 * 最后那组字段是本节点新增的「先看清数据能做什么」环节：列结构一拿到就让模型对照问题判断
 * 缺什么、该查哪些列。判定结果只往下传，不参与路由——判严了就拦断链路会重演 judge 死循环，
 * 所以 insufficient 也照常进入 sql_generate，由 SQL 尽力取数、由最终回答说明局限。
 *
 * 用户 Excel 不注册成 DuckDB 视图，模型直接用 read_xlsx('绝对路径', sheet='...') 读取，
 * 所以这里要喂给模型「路径 + 真实列名」——只给文件名模型写不出带列的 SQL。
 * 列结构由 DESCRIBE SELECT * FROM read_xlsx(...) 现场读取，避免使用缓存导致列变更不同步。
 *
 * 安全边界：allowedFilePaths 只放本分类、status=success、非 eval:// 伪路径、且真实存在于
 * workspace 内的文件；SqlGuardNode 校验 read_xlsx 的路径参数必须是这里面的值，
 * 所以模型只能读「喂给它的文件」，读不到服务器任意文件。
 *
 * 连接统一走 Spring 数据源（JdbcTemplate → jdbc:duckdb:./data/app.duckdb），
 * 不要再 DriverManager 直连：绕过连接池，且与 Hikari(size=1) 的单写者约定冲突。
 */
@Slf4j
@Service(NodeName.SCHEMA_RETRIEVE)
public class SchemaRetrieveNode implements Node {

    /** eval:// 这类伪协议是评测造数文件，没有真实 Excel，不能喂给模型读 */
    private static final String PSEUDO_PATH = "^[A-Za-z][A-Za-z0-9+.-]*://.*";

    @Autowired
    private JdbcTemplate jdbc;

    /** 数据可用性评估要对照「问题 + 真实列」做一次判断，只能靠模型 */
    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    /** 上传文件根目录：file_path 相对它存放，read_xlsx 需要绝对路径 */
    @Value("${agent.workspace}")
    private String workSpace;

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        Integer categoryId = SessionHolder.getCategory();
        log.info("[schema] >>> categoryId={}", categoryId);

        Path workspace = Path.of(workSpace).toAbsolutePath().normalize();

        // 只取本分类、解析成功的真实文件；-1（待归类）与 eval:// 伪路径都排除
        String baseSql = "select orig_name, file_path, sheet_name, row_count from file_view"
                + " where status = 'success' and category_id <> -1";
        List<SheetRow> rows = categoryId == null
                ? jdbc.query(baseSql + " order by orig_name, sheet_name", (rs, n) -> mapRow(rs))
                : jdbc.query(baseSql + " and category_id = ? order by orig_name, sheet_name",
                        (rs, n) -> mapRow(rs), categoryId);

        // 拼文件清单与白名单。同一文件路径只 DESCRIBE 一次（多 sheet 各读各的）
        Set<String> allowed = new LinkedHashSet<>();
        StringBuilder catalog = new StringBuilder();
        Set<String> seenSheet = new HashSet<>();
        for (SheetRow row : rows) {
            Path abs = resolveRealFile(workspace, row.filePath());
            if (abs == null) {
                continue;
            }
            String path = abs.toString().replace('\\', '/');
            String sheet = row.sheetName() == null ? "" : row.sheetName();
            if (!seenSheet.add(path + "|" + sheet)) {
                continue;
            }
            List<String> columns;
            try {
                columns = describeColumns(abs, sheet);
            } catch (Exception e) {
                // 单个文件读不了（被删、改名、sheet 变更）只跳过它，不影响其他文件
                log.warn("[schema] 读取 Excel 列结构失败，跳过：file={}, sheet={}, err={}",
                        path, sheet, e.getMessage());
                continue;
            }
            if (columns.isEmpty()) {
                log.warn("[schema] Excel 无列结构，跳过：file={}, sheet={}", path, sheet);
                continue;
            }
            allowed.add(path);
            catalog.append("- 文件：").append(row.origName())
                    .append("（sheet：").append(sheet).append("，")
                    .append(row.rowCount() == null ? 0 : row.rowCount()).append(" 行）\n")
                    .append("  路径：").append(path).append("\n")
                    .append("  列：").append(String.join(", ", columns)).append("\n");
        }
        Set<String> systemTables = SchemaConstant.SYSTEM_TABLES;
        // 表名白名单只留系统表：用户数据改走 read_xlsx 路径校验，
        // 不再把 Excel 文件名塞进 candidateTables 让模型误以为是表。
        state.setCandidateTables(new ArrayList<>(systemTables));
        state.setAllowedFilePaths(new ArrayList<>(allowed));
        String fileCatalog = catalog.toString().trim();
        state.setFileCatalog(fileCatalog.isEmpty() ? null : fileCatalog);
        log.info("[schema] 本分类可用文件 sheet 数={}, 去重路径数={}", seenSheet.size(), allowed.size());

        // 系统表 DDL 恒带：模型可能关联 file_view 等元数据表，不给列信息会凭空捏造字段
        String placeholders = String.join(",", Collections.nCopies(systemTables.size(), "?"));
        List<String> ddlList = jdbc.query(
                "select sql from duckdb_tables()"
                        + " where schema_name = 'main' and lower(table_name) in (" + placeholders + ")",
                (rs, rowNum) -> rs.getString("sql"), systemTables.toArray());

        // 表注释写在 COMMENT ON 里，CREATE 语句不带；补注入给模型理解系统表用途（只取系统表）。
        // 注释不是业务口径来源——用户数据的口径由澄清阶段的 assumptions 给出。
        List<String> tableComments = jdbc.query(
                "select '-- 表注释 ' || table_name || '：' || comment from duckdb_tables()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + " and lower(table_name) in (" + placeholders + ")",
                (rs, rowNum) -> rs.getString(1).replace("\n", " "), systemTables.toArray());
        List<String> columnComments = jdbc.query(
                "select '-- 列注释 ' || table_name || '.' || column_name || '：' || comment from duckdb_columns()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + " and lower(table_name) in (" + placeholders + ")"
                        + " order by table_name, column_index",
                (rs, rowNum) -> rs.getString(1).replace("\n", " "), systemTables.toArray());

        StringBuilder sb = new StringBuilder();
        ddlList.stream().filter(Objects::nonNull).forEach(ddl -> sb.append(ddl).append('\n'));
        if (!tableComments.isEmpty() || !columnComments.isEmpty()) {
            sb.append("\n-- 系统表说明（COMMENT ON TABLE / COLUMN），只描述系统表用途，不是业务口径：\n");
            tableComments.forEach(line -> sb.append(line).append('\n'));
            columnComments.forEach(line -> sb.append(line).append('\n'));
        }
        String tableSchemas = sb.toString().trim();
        state.setTableSchemas(tableSchemas.isEmpty() ? null : tableSchemas);
        if (fileCatalog.isEmpty()) {
            log.warn("[schema] 本分类没有可读的真实文件，模型只能查系统表；categoryId={}", categoryId);
        }

        // 列结构已就绪，此时判断「这些数据能不能回答问题」最省事：再往下就得先写 SQL 才发现缺列
        assessDataCapability(state, fileCatalog);

        log.info("[schema] <<< cost={}ms, 系统表数={}, tableSchemas 字符数={}, fileCatalog 字符数={}, 数据判断={}",
                System.currentTimeMillis() - start, ddlList.size(), tableSchemas.length(),
                fileCatalog.length(), state.getDataSufficiency());
    }

    /**
     * 数据可用性评估：把「澄清后的问题 + 真实列结构」交给模型，产出
     * ① 能不能满足（sufficient / partial / insufficient）
     * ② 缺哪些列或维度
     * ③ 建议查哪些列、怎么算（传给 sql_generate）
     * ④ 一句给用户的局限说明（传给 judge 生成最终回答）
     *
     * 失败一律降级为「未评估」并继续：本节点是必经之路，不能因为一次评估调用
     * 把整轮问答打断；下游 sql_generate / judge 在没有评估结果时本来就能独立工作。
     */
    private void assessDataCapability(AnalysisState state, String fileCatalog) {
        if (fileCatalog.isEmpty()) {
            // 没有文件就不用调模型：结论是确定的，省一次调用与几秒延迟
            state.setDataSufficiency("insufficient");
            state.setDataLimitations(new String[]{"当前分类下没有可读的表格文件"});
            state.setDataLimitationNote("当前分类还没有可读取的表格，无法回答数据类问题。");
            log.info("[schema] 无可用文件，直接判定 insufficient");
            return;
        }
        String question = state.getClarifiedQuestion() != null && !state.getClarifiedQuestion().isBlank()
                ? state.getClarifiedQuestion() : state.getQuestion();
        long assessStart = System.currentTimeMillis();
        try {
            String raw = llmClient.call(List.of(
                    new SystemMessage(prompt("message/SchemaRetrieveNode.txt")),
                    new UserMessage("用户问题：" + question + "\n\n可用表格（含真实列）：\n" + fileCatalog)));
            log.debug("[schema] 数据评估模型返回原文：{}", raw);
            DataCapability entity = objectMapper.readValue(extractJson(raw), DataCapability.class);
            state.setDataSufficiency(normalizeSufficiency(entity.sufficiency()));
            state.setDataLimitations(entity.missing() == null
                    ? new String[0] : entity.missing().toArray(new String[0]));
            state.setQueryPlan(blankToNull(entity.queryPlan()));
            state.setDataLimitationNote(blankToNull(entity.limitation()));
            log.info("[schema] 数据评估完成 cost={}ms, 判断={}, 缺失={}, 查询建议={}",
                    System.currentTimeMillis() - assessStart, state.getDataSufficiency(),
                    state.getDataLimitations().length, state.getQueryPlan());
        } catch (Exception e) {
            // 解析失败 / 调用超时 / 返回非 JSON：留空即「未评估」，提示词侧已按「无评估」兜底
            log.warn("[schema] 数据可用性评估失败，降级为未评估继续；cost={}ms, err={}",
                    System.currentTimeMillis() - assessStart, e.getMessage());
        }
    }

    /** 只接受三个约定值，模型自造词一律折成 unknown，避免下游按字符串比较时误判 */
    private static String normalizeSufficiency(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return switch (raw.trim().toLowerCase()) {
            case "sufficient", "partial", "insufficient" -> raw.trim().toLowerCase();
            default -> "unknown";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 只截取 JSON 本体，兼容 ```json 围栏；找不到完整 JSON 时抛异常由调用方降级 */
    private static String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("模型返回为 null");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型返回中未找到 JSON");
        }
        return raw.substring(start, end + 1);
    }

    private String prompt(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[schema] 提示词读取失败 path={}", path, e);
            throw new RuntimeException(e);
        }
    }

    /** 把 file_path 解析成 workspace 内真实存在的绝对路径；伪路径/越界/不存在都返回 null */
    private Path resolveRealFile(Path workspace, String filePath) {
        if (filePath == null || filePath.isBlank() || filePath.matches(PSEUDO_PATH)) {
            return null;
        }
        try {
            Path p = Path.of(filePath);
            Path abs = (p.isAbsolute() ? p : workspace.resolve(p)).normalize();
            if (!abs.startsWith(workspace) || !Files.isRegularFile(abs)) {
                return null;
            }
            return abs;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 用 DESCRIBE 现场读列结构，避免缓存；sheet 为空时读第一个工作表 */
    private List<String> describeColumns(Path abs, String sheet) {
        String pathLiteral = abs.toString().replace('\\', '/').replace("'", "''");
        String from = sheet.isEmpty()
                ? "read_xlsx('" + pathLiteral + "')"
                : "read_xlsx('" + pathLiteral + "', sheet = '"
                        + sheet.replace("'", "''") + "')";
        return jdbc.query("DESCRIBE SELECT * FROM " + from,
                (rs, n) -> rs.getString("column_name") + " " + rs.getString("column_type"));
    }

    private SheetRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SheetRow(rs.getString("orig_name"), rs.getString("file_path"),
                rs.getString("sheet_name"), rs.getLong("row_count"));
    }

    private record SheetRow(String origName, String filePath, String sheetName, Long rowCount) {}
}

/**
 * 数据可用性评估的 LLM 返回结构。
 * 提示词用 snake_case，这里必须显式 @JsonProperty：Jackson 默认按字段名精确匹配，
 * 漏写会静默得到全 null（与 SqlGenerateEntity 同一坑）。
 */
record DataCapability(
        /** sufficient / partial / insufficient */
        @JsonProperty("sufficiency")
        String sufficiency,

        /** 缺失的列、维度或口径；无缺失时为空数组 */
        @JsonProperty("missing")
        List<String> missing,

        /** 建议的查询路径：读哪个文件、用哪些列、怎么算 */
        @JsonProperty("query_plan")
        String queryPlan,

        /** 一句面向用户的局限说明；无局限时为空串 */
        @JsonProperty("limitation")
        String limitation
) {}
