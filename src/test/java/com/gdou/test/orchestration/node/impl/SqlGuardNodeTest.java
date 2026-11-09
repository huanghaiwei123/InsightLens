package com.gdou.test.orchestration.node.impl;

import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.impl.SqlGuardNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ④ 安全校验节点的七条规则。纯代码判定、不经过模型也不连数据库，
 * 是「写操作拦截 100%」这条验收标准唯一可自动回归的地方。
 */
class SqlGuardNodeTest {

    private static final RunContext CTX = new RunContext("guard-test", new NoOpStreamSink());
    private static final String DDL_WITH_TIME =
            "CREATE TABLE sales(pay_time TIMESTAMP, amount DOUBLE, category VARCHAR);";

    private SqlGuardNode guard;
    private AnalysisState state;

    @BeforeEach
    void setUp() {
        guard = new SqlGuardNode();
        state = new AnalysisState();
        state.setCandidateTables(List.of("sales", "refund_detail"));
    }

    private void check(String sql) {
        state.setSql(sql);
        guard.apply(state, CTX);
    }

    private void assertPass() {
        assertThat(state.getValidationResult()).isEqualTo("pass");
        assertThat(state.getError()).isNull();
    }

    private void assertReject(String reasonPart) {
        assertThat(state.getValidationResult()).isEqualTo("reject");
        assertThat(state.getError()).contains(reasonPart);
    }

    // ---------- ① 仅 SELECT ----------

    @Test
    @DisplayName("① 非 SELECT 开头一律拒绝")
    void rejectNonSelect() {
        check("delete from sales where 1=1");
        assertReject("仅允许 SELECT");
    }

    @Test
    @DisplayName("① WITH 开头的 CTE 属于查询，应正常放行")
    void allowCteThroughFirstRule() {
        // CTE 名也可能被表名提取器当作表名，一并加入白名单，只考察规则①
        state.setCandidateTables(List.of("sales", "x"));
        check("with x as (select * from sales limit 10) select * from x limit 5");
        assertPass();
    }

    // ---------- ② 单语句 ----------

    @Test
    @DisplayName("② 分号拼接多语句拒绝")
    void rejectMultipleStatements() {
        check("select a from sales; select b from sales");
        assertReject("分号");
    }

    @Test
    @DisplayName("② 末尾单个分号允许，且自动补 LIMIT 时不残留分号")
    void allowTrailingSemicolon() {
        check("select a from sales;");
        assertPass();
        assertThat(state.getSql()).doesNotContain(";").endsWith("LIMIT 200");
    }

    // ---------- ③ 危险关键词与剥壳 ----------

    @Test
    @DisplayName("③ 字符串字面量里的 DROP 不误杀")
    void ignoreKeywordInsideStringLiteral() {
        check("select note from sales where note = 'drop table'");
        assertPass();
    }

    @Test
    @DisplayName("③ 行注释里的 DROP 不误杀")
    void ignoreKeywordInsideLineComment() {
        check("select a from sales -- drop table\n where a > 0");
        assertPass();
    }

    @Test
    @DisplayName("③ 块注释里的 DROP 不误杀")
    void ignoreKeywordInsideBlockComment() {
        check("select a /* drop table */ from sales");
        assertPass();
    }

    @Test
    @DisplayName("③ 关键词出现在语句任意位置都要拦（CTE 内也不能漏）")
    void rejectKeywordAnywhere() {
        check("with x as (truncate table sales) select * from sales");
        assertReject("包含禁止的关键词");
    }

    // ---------- ⑥ 外部文件 ----------

    @Test
    @DisplayName("⑥ read_* 表函数读外部文件一律拒绝")
    void rejectExternalFileRead() {
        check("select * from read_csv_auto('/etc/passwd')");
        assertReject("禁止读取外部文件");
    }

    // ---------- ⑤ 表名白名单 ----------

    @Test
    @DisplayName("⑤ 白名单之外的表拒绝")
    void rejectTableOutsideWhitelist() {
        check("select * from secret_table limit 10");
        assertReject("白名单之外");
    }

    @Test
    @DisplayName("⑤ 子查询里的表也要经过白名单")
    void rejectTableHiddenInSubquery() {
        check("select * from (select * from secret_table) s limit 10");
        assertReject("白名单之外");
    }

    @Test
    @DisplayName("⑤ 七张系统表在白名单内")
    void allowSystemTables() {
        check("select * from table_registry limit 10");
        assertPass();
    }

    @Test
    @DisplayName("⑤ 候选表与表名大小写不敏感")
    void allowCandidateTableCaseInsensitive() {
        check("SELECT * FROM SALES LIMIT 10");
        assertPass();
        assertThat(state.getSql()).isEqualTo("SELECT * FROM SALES LIMIT 10");
    }

    // ---------- ④ LIMIT ----------

    @Test
    @DisplayName("④ 缺 LIMIT 自动追加 200，属于格式修正不算拒绝")
    void appendLimitWhenMissing() {
        check("select a from sales");
        assertPass();
        assertThat(state.getSql()).endsWith("LIMIT 200");
    }

    @Test
    @DisplayName("④ 已有 LIMIT 时原样保留")
    void keepExistingLimit() {
        check("select a from sales limit 5");
        assertPass();
        assertThat(state.getSql()).isEqualTo("select a from sales limit 5");
    }

    @Test
    @DisplayName("pass 时必须清空上一轮 reject 残留的 error，避免误回修正循环")
    void clearPreviousErrorOnPass() {
        state.setError("上一轮：白名单之外的表");
        check("select a from sales limit 5");
        assertPass();
        assertThat(state.getError()).isNull();
    }

    // ---------- ⑦ 时间口径（窄化版） ----------

    @Test
    @DisplayName("⑦ 引用时间列且无时间边界、无聚合、无 LIMIT 时拒绝")
    void rejectTimeColumnWithoutBoundary() {
        state.setTableSchemas(DDL_WITH_TIME);
        check("select pay_time, amount from sales");
        assertReject("没有任何时间边界");
    }

    @Test
    @DisplayName("⑦ 有 WHERE 但条件里不含时间列，同样拒绝")
    void rejectWhenWhereHasNoTimeCondition() {
        state.setTableSchemas(DDL_WITH_TIME);
        check("select pay_time from sales where amount > 10");
        assertReject("没有任何时间边界");
    }

    @Test
    @DisplayName("⑦ WHERE 里带时间条件则放行")
    void allowWhenWhereHasTimeCondition() {
        state.setTableSchemas(DDL_WITH_TIME);
        check("select pay_time from sales where pay_time >= '2026-01-01'");
        assertPass();
        assertThat(state.getSql()).endsWith("LIMIT 200");
    }

    @Test
    @DisplayName("⑦ 已有 LIMIT 时不拦时间口径")
    void allowTimeColumnWithLimit() {
        state.setTableSchemas(DDL_WITH_TIME);
        check("select pay_time from sales limit 10");
        assertPass();
    }

    @Test
    @DisplayName("⑦ 聚合查询的时间口径问题交给 judge，不在这里拦")
    void allowAggregationWithoutTimeBoundary() {
        state.setTableSchemas(DDL_WITH_TIME);
        check("select pay_time, sum(amount) from sales group by pay_time");
        assertPass();
        assertThat(state.getSql()).endsWith("LIMIT 200");
    }

    @Test
    @DisplayName("⑦ 拿不到 DDL 时跳过时间规则，不因信息缺失误拒")
    void skipTimeRuleWhenDdlMissing() {
        check("select pay_time from sales");
        assertPass();
    }

    // ---------- 边界 ----------

    @Test
    @DisplayName("SQL 为空或全空白时拒绝")
    void rejectBlankSql() {
        for (String sql : new String[]{null, "", "   "}) {
            setUp();
            check(sql);
            assertReject("SQL 为空");
        }
    }
}
