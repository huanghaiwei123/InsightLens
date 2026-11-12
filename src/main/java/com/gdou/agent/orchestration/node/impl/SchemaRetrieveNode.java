package com.gdou.agent.orchestration.node.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.api.mapper.TableRegistryMapper;
import com.gdou.agent.meta.entity.TableRegistry;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ② Schema 检索节点
 * 入：clarify 选中的表名（state.candidateTables，可能为空）
 * 出：candidateTables（空时兜底为注册表全量）、tableSchemas（选中表 + 系统表的 DDL）
 *
 * 连接统一走 Spring 数据源（JdbcTemplate → jdbc:duckdb:./data/app.duckdb），
 * 不要再 DriverManager 直连：绕过连接池，且与 Hikari(size=1) 的单写者约定冲突。
 */
@Slf4j
@Service(NodeName.SCHEMA_RETRIEVE)
public class SchemaRetrieveNode implements Node {

    /** 系统表 DDL 恒定附带：提示词允许模型关联 upload_batch 等表界定批次，必须提供列信息 */
    private static final Set<String> SYSTEM_TABLES = Set.of(
            "session", "message", "table_registry", "upload_batch",
            "evaluation_run", "eval_case_result", "trace_span");

    private final JdbcTemplate jdbc;
    private final TableRegistryMapper tableRegistryMapper;

    public SchemaRetrieveNode(JdbcTemplate jdbc, TableRegistryMapper tableRegistryMapper) {
        this.jdbc = jdbc;
        this.tableRegistryMapper = tableRegistryMapper;
    }

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        log.info("[schema] >>> 入参 candidateTables={}", state.getCandidateTables());

        // clarify 选中的表；为空（模型没选 / 库里还没表）时兜底为注册表全量
        if (state.getCandidateTables() == null || state.getCandidateTables().isEmpty()) {
            LambdaQueryWrapper<TableRegistry> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(TableRegistry::getTableName);
            state.setCandidateTables(tableRegistryMapper.selectList(wrapper)
                    .stream().map(TableRegistry::getTableName).toList());
            log.info("[schema] clarify 未选表，兜底为注册表全量：{}", state.getCandidateTables());
        }

        // DDL 取「选中的用户表 + 全部系统表」：系统表不进 clarify 清单但模型可能关联它们，
        // 不给列信息模型就会凭空捏造字段名
        List<String> wantTables = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (String t : state.getCandidateTables()) {
            if (t != null && distinct.add(t.toLowerCase())) {
                wantTables.add(t.toLowerCase());
            }
        }
        for (String t : SYSTEM_TABLES) {
            if (distinct.add(t)) {
                wantTables.add(t);
            }
        }

        String placeholders = String.join(",", Collections.nCopies(wantTables.size(), "?"));
        String sql = "select table_name, sql from duckdb_tables()"
                + " where schema_name = 'main' and lower(table_name) in (" + placeholders + ")";
        log.debug("[schema] 查询 DDL：{} | 参数={}", sql, wantTables);

        List<String> ddlList = jdbc.query(sql, (rs, rowNum) -> rs.getString("sql"), wantTables.toArray());
        String ddl = ddlList.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        // 口径词典写在业务表的 COMMENT ON TABLE / COLUMN 里，DuckDB 存进 catalog，
        // 但 duckdb_tables().sql 只有 CREATE TABLE 语句、不带注释。这里补注入，
        // 否则模型只看到列名与类型，不知道 status 枚举、GMV / 退款率口径、时间边界。
        // 只取 table_registry 登记的业务表：7 张系统表带几十行列注释，全塞会稀释提示词。
        String businessOnly = " and lower(table_name) in (select lower(table_name) from table_registry)";
        List<String> tableComments = jdbc.query(
                "select '-- 表注释 ' || table_name || '：' || comment from duckdb_tables()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + businessOnly,
                (rs, rowNum) -> rs.getString(1).replace("\n", " "));
        List<String> columnComments = jdbc.query(
                "select '-- 列注释 ' || table_name || '.' || column_name || '：' || comment from duckdb_columns()"
                        + " where schema_name = 'main' and comment is not null and comment <> ''"
                        + businessOnly
                        + " order by table_name, column_index",
                (rs, rowNum) -> rs.getString(1).replace("\n", " "));

        StringBuilder sb = new StringBuilder(ddl);
        if (!tableComments.isEmpty() || !columnComments.isEmpty()) {
            sb.append("\n\n-- 库内登记的业务口径（COMMENT ON TABLE / COLUMN），与表结构同等权威：\n");
            tableComments.forEach(line -> sb.append(line).append('\n'));
            columnComments.forEach(line -> sb.append(line).append('\n'));
        }
        String tableSchemas = sb.toString().trim();
        state.setTableSchemas(tableSchemas.isEmpty() ? null : tableSchemas);
        if (ddl.isEmpty()) {
            log.warn("[schema] 未取到任何 DDL，后续 SQL 生成将缺少表结构；wantTables={}", wantTables);
        }
        log.info("[schema] <<< cost={}ms, 请求表数={}, 命中 DDL 条数={}, 口径注释行数={}, tableSchemas 字符数={}",
                System.currentTimeMillis() - start, wantTables.size(), ddlList.size(),
                tableComments.size() + columnComments.size(), tableSchemas.length());
    }
}
