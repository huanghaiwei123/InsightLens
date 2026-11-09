package com.gdou.agent.orchestration.node.impl;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ④ 安全校验节点（确定性代码，不经过模型）
 *
 * 七条规则（规则 ⑦ 按讨论定稿为窄化版）：
 *   ① 仅 SELECT            —— 语句必须以 select / with 开头
 *   ② 单语句               —— 剥掉字符串字面量与注释后不允许出现分号
 *   ③ 无 DDL/DML           —— 危险关键词黑名单，覆盖 CTE 与函数调用内的隐藏写法
 *   ④ 必须带 LIMIT          —— 缺失时自动追加 LIMIT 200（格式问题，不算安全拒绝）
 *   ⑤ 表名白名单           —— 候选表 + 系统表之外一律拒绝
 *   ⑥ 禁止跨库/外部文件     —— ATTACH 由黑名单覆盖，read_* 表函数单独拦截
 *   ⑦ 时间口径显式（窄化版）—— 仅当「引用时间字段 && 无时间条件 && 无聚合 && 无 LIMIT」
 *                              四者同时成立才拒绝；其余时间口径问题（如聚合无边界）
 *                              属生成质量问题，交 judge 判 off_target 走修正循环。
 *
 * 不通过：validationResult = "reject"，具体原因写入 state.error。
 * 校验结论与错误原因分离：前者是 GraphRunner 的路由信号（pass → ⑤ / reject → ③），
 * 后者是给 sql_generate 的可操作输入（SqlGenerateNode 据此把上一轮 SQL 连同原因回填给模型）。
 * 通过时必须清空上一轮残留的 error，否则 nextSqlJudge 会把成功路径误判为失败再回 ③。
 */
@Slf4j
@Service(NodeName.SQL_GUARD)
public class SqlGuardNode implements Node {

    /** 系统表：提示词约定模型可查这 7 张元数据表，与候选表共同构成白名单 */
    private static final Set<String> SYSTEM_TABLES = Set.of(
            "session", "message", "table_registry", "upload_batch",
            "evaluation_run", "eval_case_result", "trace_span");

    /** 危险关键词：出现在语句任意位置（含 CTE 内部）即拒绝 */
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|create|alter|truncate|grant|revoke|merge"
                    + "|attach|detach|copy|export|import|install|load|call|set|reset"
                    + "|pragma|vacuum|checkpoint)\\b");

    /** DuckDB 读外部文件的表函数：read_csv / read_csv_auto / read_parquet / read_json ... */
    private static final Pattern READ_FILE_FUNCTIONS = Pattern.compile("\\bread_\\w+\\s*\\(");

    /** FROM / JOIN 后面跟的表名（正则兜底用，主路径走 JSqlParser） */
    private static final Pattern FROM_OR_JOIN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+\"?([a-z_][a-z0-9_$]*)\"?");

    private static final Pattern HAS_LIMIT = Pattern.compile("(?i)\\b(limit\\s+\\d+|fetch\\s+first)");

    private static final Pattern HAS_AGGREGATION = Pattern.compile(
            "(?i)(\\bgroup\\s+by\\b|\\b(avg|sum|count|min|max)\\s*\\()");

    /** 从 DDL 文本提取时间类型列名：如 order_date TIMESTAMP / 日期 DATE */
    private static final Pattern TIME_COLUMN_IN_DDL = Pattern.compile(
            "(?im)\"?([\\p{L}_][\\p{L}\\p{N}_]*)\"?\\s+(?:TIMESTAMP(\\s+WITH\\s+TIME\\s+ZONE)?|DATE|TIME)\\b");

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        String sql = state.getSql();
        log.info("[sql-guard] >>> 待校验 sql={}", sql);
        if (sql == null || sql.isBlank()) {
            reject(state, "SQL 为空");
            return;
        }

        // 先剥掉字符串字面量与注释，后续所有关键词扫描都基于剥壳文本，
        // 防止 WHERE name = 'drop table' 这类字面量误杀
        String stripped = stripLiteralsAndComments(sql).trim();
        String norm = stripped.toLowerCase().replaceAll("\\s+", " ").trim();

        // ① 仅 SELECT（CTE 以 with 开头也算查询）
        if (!norm.startsWith("select ") && !norm.startsWith("select*") && !norm.startsWith("with ")) {
            reject(state, "仅允许 SELECT 查询");
            return;
        }

        // ② 单语句：容忍末尾一个分号，句中出现分号即拒绝
        String body = norm.endsWith(";") ? norm.substring(0, norm.length() - 1) : norm;
        if (body.contains(";")) {
            reject(state, "禁止分号拼接多条语句");
            return;
        }

        // ③ DDL/DML 关键词黑名单
        Matcher forbidden = FORBIDDEN_KEYWORDS.matcher(body);
        if (forbidden.find()) {
            reject(state, "包含禁止的关键词：" + forbidden.group());
            return;
        }

        // ⑥ 禁止读取外部文件（ATTACH 已被黑名单覆盖）
        if (READ_FILE_FUNCTIONS.matcher(body).find()) {
            reject(state, "禁止读取外部文件");
            return;
        }

        // ⑤ 表名白名单：主路径用 JSqlParser 提取全部表名（含子查询），
        //    解析失败时退化为 FROM/JOIN 正则——宁可粗一点，不能因为方言问题放行
        Set<String> whitelist = new HashSet<>(SYSTEM_TABLES);
        if (state.getCandidateTables() != null) {
            for (String t : state.getCandidateTables()) {
                whitelist.add(t.toLowerCase());
            }
        }
        log.debug("[sql-guard] 本轮表名白名单={}", whitelist);
        for (String table : extractTables(stripped)) {
            if (!whitelist.contains(table.toLowerCase())) {
                reject(state, "查询了白名单之外的表：" + table);
                return;
            }
        }

        boolean hasLimit = HAS_LIMIT.matcher(body).find();

        // ⑦ 时间口径（窄化版，见类注释）
        if (!hasLimit
                && !HAS_AGGREGATION.matcher(body).find()
                && violatesTimeScope(state, body)) {
            reject(state, "引用了时间字段但没有任何时间边界");
            return;
        }

        // ④ 必须带 LIMIT：缺失时自动追加
        if (!hasLimit) {
            if (sql.trim().endsWith(";")) {
                sql = sql.trim().substring(0, sql.trim().length() - 1);
            }
            sql = sql + " LIMIT 200";
            state.setSql(sql);
        }

        state.setValidationResult("pass");
        // 上一轮 reject 写进 error 的原因已消费完，必须清掉：
        // nextSqlJudge 以 error == null 作为成功条件，残留会导致再次回 ③
        state.setError(null);
        log.info("[sql-guard] <<< pass cost={}ms, 自动补 LIMIT={}, finalSql={}",
                System.currentTimeMillis() - start, !hasLimit, state.getSql());
    }

    /**
     * 规则 ⑦：SQL 引用了时间列，但 WHERE 子句里没有任何时间列的条件。
     * 时间列从 tableSchemas 的 DDL 文本中提取；tableSchemas 为空则跳过本条。
     */
    private boolean violatesTimeScope(AnalysisState state, String norm) {
        Set<String> timeColumns = extractTimeColumns(state.getTableSchemas());
        if (timeColumns.isEmpty()) {
            return false;
        }
        boolean referencesTime = timeColumns.stream().anyMatch(col -> containsWord(norm, col));
        if (!referencesTime) {
            return false;
        }
        int whereIdx = norm.indexOf(" where ");
        if (whereIdx < 0) {
            return true;
        }
        String whereClause = norm.substring(whereIdx);
        return timeColumns.stream().noneMatch(col -> containsWord(whereClause, col));
    }

    private Set<String> extractTimeColumns(String tableSchemas) {
        if (tableSchemas == null || tableSchemas.isBlank()) {
            return Set.of();
        }
        Set<String> cols = new HashSet<>();
        Matcher m = TIME_COLUMN_IN_DDL.matcher(tableSchemas);
        while (m.find()) {
            cols.add(m.group(1).toLowerCase());
        }
        return cols;
    }

    /** 提取 SQL 引用的全部表名：JSqlParser 优先，解析失败退化为 FROM/JOIN 正则 */
    private Set<String> extractTables(String stripped) {
        try {
            Statement statement = CCJSqlParserUtil.parse(stripped);
            return new TablesNamesFinder().getTables(statement);
        } catch (Exception e) {
            Set<String> tables = new HashSet<>();
            Matcher m = FROM_OR_JOIN.matcher(stripped);
            while (m.find()) {
                tables.add(m.group(1));
            }
            return tables;
        }
    }

    /**
     * 剥掉字符串字面量与注释：
     * - 'abc''s' → ''（保留成对引号使文本仍可解析，内容丢弃）
     * - -- 行注释、块注释 整段丢弃
     * 关键词扫描只看剥壳后的文本，字面量里的 DROP 不算语句。
     */
    private String stripLiteralsAndComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++; // 转义的单引号 ''
                        continue;
                    }
                    inString = false;
                    sb.append('\'');
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                sb.append('\'');
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private boolean containsWord(String text, String word) {
        Pattern p = Pattern.compile(
                "(^|[^\\p{L}\\p{N}_])" + Pattern.quote(word) + "($|[^\\p{L}\\p{N}_])");
        return p.matcher(text).find();
    }

    /**
     * 所有规则的统一拒绝出口：在这里记一条 WARN，七条规则不必各自埋点，
     * 排障时按 [sql-guard] reject 一捞就能看到被拦的原因与原始 SQL。
     */
    private void reject(AnalysisState state, String reason) {
        state.setValidationResult("reject");
        state.setError(reason);
        log.warn("[sql-guard] reject reason={}, sql={}", reason, state.getSql());
    }
}
