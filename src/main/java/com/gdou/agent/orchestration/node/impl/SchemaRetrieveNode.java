package com.gdou.agent.orchestration.node.impl;

import com.gdou.agent.api.holder.SessionHolder;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ② Schema 检索节点
 * 入：当前窗口的 categoryId（SessionHolder）
 * 出：candidateTables（分类下全部可用视图，同时作为下游表名白名单）、tableSchemas（这些视图 + 系统表的列结构）
 *
 * 选表机制已废弃：不再由 clarify 模型挑表，这里直接按分类把 file_view 里 status=success 的表全量收集。
 * 表名由此成为「可用表清单」——代表能用哪些表，不代表这次该用哪些表，由 SQL 生成节点自行判断相关性。
 *
 * 用户表都是 DuckDB VIEW（CREATE VIEW ... AS SELECT * FROM read_xlsx(...)），
 * duckdb_tables().sql 拿到的是 CREATE VIEW 定义、不含列名与类型，模型无法据此写 SQL。
 * 因此用户表结构改从 duckdb_columns() 逐列拼成 CREATE TABLE 风格；系统表仍直接取 duckdb_tables().sql。
 *
 * 连接统一走 Spring 数据源（JdbcTemplate → jdbc:duckdb:./data/app.duckdb），
 * 不要再 DriverManager 直连：绕过连接池，且与 Hikari(size=1) 的单写者约定冲突。
 */
@Slf4j
@Service(NodeName.SCHEMA_RETRIEVE)
public class SchemaRetrieveNode implements Node {

    /** 系统表 DDL 恒定附带：提示词允许模型查这些元数据表界定批次，必须提供列信息 */
    private static final Set<String> SYSTEM_TABLES = Set.of(
            "session", "message", "category", "file_view",
            "evaluation_run", "eval_case_result", "trace_span");

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        Integer categoryId = SessionHolder.getCategory();
        log.info("[schema] >>> categoryId={}", categoryId);

        // 选表废弃后无条件按分类全量收集：candidateTables 是「可用表清单」，
        // 既是本节点取 DDL 的范围，也是 SqlGuardNode 的表名白名单。
        // 列名用 file_view.orig_name（原始文件名），与 ClarifyNode 展示给模型的候选清单一致。
        String sql = "select orig_name from file_view where status = 'success'";
        List<String> names = categoryId == null
                ? jdbc.queryForList(sql + " order by orig_name", String.class)
                : jdbc.queryForList(sql + " and category_id = ? order by orig_name", String.class, categoryId);
        state.setCandidateTables(names);
        log.info("[schema] 按分类收集可用表 categoryId={}：{}", categoryId, names);

        // DDL 取「分类下全部用户视图 + 全部系统表」：系统表不进候选清单但模型可能关联它们，
        // 不给列信息模型就会凭空捏造字段名
        List<String> wantTables = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (String t : state.getCandidateTables()) {
            if (t != null && distinct.add(t.toLowerCase())) {
                wantTables.add(t.toLowerCase());
            }
        }
        List<String> systemNames = new ArrayList<>();
        for (String t : SYSTEM_TABLES) {
            if (distinct.add(t)) {
                wantTables.add(t);
                systemNames.add(t);
            }
        }
        if (wantTables.isEmpty()) {
            log.warn("[schema] 没有任何要检索的表，跳过 DDL 查询");
            state.setTableSchemas(null);
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(wantTables.size(), "?"));

        // 系统表：duckdb_tables().sql 就是 CREATE TABLE，直接取
        String sysPlaceholders = String.join(",", Collections.nCopies(systemNames.size(), "?"));
        List<String> ddlList = jdbc.query(
                "select sql from duckdb_tables()"
                        + " where schema_name = 'main' and lower(table_name) in (" + sysPlaceholders + ")",
                (rs, rowNum) -> rs.getString("sql"), systemNames.toArray());

        // 用户表是 VIEW：逐列拼成 CREATE TABLE 风格，让模型看到真实列名与类型
        List<String> userViews = wantTables.stream()
                .filter(t -> !SYSTEM_TABLES.contains(t)).toList();
        Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
        if (!userViews.isEmpty()) {
            String viewPlaceholders = String.join(",", Collections.nCopies(userViews.size(), "?"));
            jdbc.query("select lower(table_name) as t, column_name, data_type from duckdb_columns()"
                            + " where schema_name = 'main' and lower(table_name) in (" + viewPlaceholders + ")"
                            + " order by table_name, column_index",
                    rs -> {
                        columnsByTable.computeIfAbsent(rs.getString("t"), k -> new ArrayList<>())
                                .add(rs.getString("column_name") + " " + rs.getString("data_type"));
                    },
                    userViews.toArray());
        }

        StringBuilder ddlSb = new StringBuilder();
        ddlList.stream().filter(Objects::nonNull).forEach(ddl -> ddlSb.append(ddl).append('\n'));
        columnsByTable.forEach((table, cols) -> ddlSb.append("CREATE TABLE ").append(table)
                .append("(").append(String.join(", ", cols)).append(");\n"));

        // 口径词典写在视图/表的 COMMENT ON TABLE / COLUMN 里，DuckDB 存进 catalog，
        // 但 CREATE 语句不带注释。这里补注入，否则模型只看到列名与类型，
        // 不知道 status 枚举、GMV / 退款率口径、时间边界。
        // 只取本批命中的表：全库注释一起塞会稀释提示词。
        String businessOnly = " and lower(table_name) in (" + placeholders + ")";
        List<String> tableComments = jdbc.query(
                "select '-- 表注释 ' || table_name || '：' || comment from duckdb_tables()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + businessOnly,
                (rs, rowNum) -> rs.getString(1).replace("\n", " "), wantTables.toArray());
        List<String> columnComments = jdbc.query(
                "select '-- 列注释 ' || table_name || '.' || column_name || '：' || comment from duckdb_columns()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + businessOnly
                        + " order by table_name, column_index",
                (rs, rowNum) -> rs.getString(1).replace("\n", " "), wantTables.toArray());

        StringBuilder sb = new StringBuilder(ddlSb.toString().trim());
        if (!tableComments.isEmpty() || !columnComments.isEmpty()) {
            sb.append("\n\n-- 库内登记的业务口径（COMMENT ON TABLE / COLUMN），与表结构同等权威：\n");
            tableComments.forEach(line -> sb.append(line).append('\n'));
            columnComments.forEach(line -> sb.append(line).append('\n'));
        }
        String tableSchemas = sb.toString().trim();
        state.setTableSchemas(tableSchemas.isEmpty() ? null : tableSchemas);
        if (ddlSb.length() == 0) {
            log.warn("[schema] 未取到任何列结构，后续 SQL 生成将缺少表结构；wantTables={}", wantTables);
        }
        log.info("[schema] <<< cost={}ms, 请求表数={}, 视图数={}, 口径注释行数={}, tableSchemas 字符数={}",
                System.currentTimeMillis() - start, wantTables.size(), columnsByTable.size(),
                tableComments.size() + columnComments.size(), tableSchemas.length());
    }
}
