package com.gdou.agent.orchestration.node.impl;

import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ② Schema 检索节点：验证「喂给模型的是本分类真实 Excel 的路径 + 列结构」，
 * 以及路径白名单只包含安全文件（-1 待归类、eval:// 伪路径、已删除文件都排除）。
 */
class SchemaRetrieveNodeTest {

    private static final RunContext CTX = new RunContext("schema-test", new NoOpStreamSink());

    private Connection duckConn;
    private JdbcTemplate jdbc;
    private SchemaRetrieveNode node;
    private Path workspace;
    private AnalysisState state;

    @BeforeEach
    void setUp() throws Exception {
        // 独立临时库 + 临时 workspace，不碰真实 app.duckdb
        Path tmp = Files.createTempDirectory("schema-node-test");
        workspace = Files.createDirectories(tmp.resolve("excel"));
        Path excel = workspace.resolve("会员充值.xlsx");
        Files.copy(new ClassPathResource("test-excel/会员充值.xlsx").getInputStream(), excel);

        duckConn = DriverManager.getConnection("jdbc:duckdb:");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(duckConn, true));
        jdbc.execute("LOAD excel");
        jdbc.execute("CREATE TABLE file_view(id INTEGER, category_id INTEGER, orig_name VARCHAR, "
                + "file_path VARCHAR, sheet_name VARCHAR, row_count BIGINT, status VARCHAR)");

        node = new SchemaRetrieveNode();
        ReflectionTestUtils.setField(node, "jdbc", jdbc);
        ReflectionTestUtils.setField(node, "workSpace", workspace.toString());
        state = new AnalysisState();
    }

    @AfterEach
    void tearDown() throws Exception {
        com.gdou.agent.infra.holder.SessionHolder.remove();
        if (duckConn != null) {
            duckConn.close();
        }
    }

    private void addFile(int categoryId, String origName, String filePath,
                         String sheet, long rows, String status) {
        jdbc.update("INSERT INTO file_view VALUES(?,?,?,?,?,?,?)",
                1, categoryId, origName, filePath, sheet, rows, status);
    }

    @Test
    @DisplayName("按分类产出文件路径 + 真实列结构，并写入路径白名单")
    void buildsCatalogAndWhitelist() {
        addFile(3, "会员充值.xlsx", "会员充值.xlsx", "充值", 3, "success");

        node.apply(state, CTX);

        String abs = workspace.resolve("会员充值.xlsx").toString().replace('\\', '/');
        assertThat(state.getFileCatalog())
                .contains("会员充值.xlsx")
                .contains("sheet：充值")
                .contains("路径：" + abs)
                .contains("会员ID", "会员等级", "充值日期", "充值金额");
        assertThat(state.getAllowedFilePaths()).containsExactly(abs);
        // 系统表清单不再混入用户文件名
        assertThat(state.getCandidateTables()).contains("file_view").doesNotContain("会员充值.xlsx");
    }

    @Test
    @DisplayName("待归类(-1)、eval:// 伪路径、其他分类、不存在的文件都不进白名单")
    void excludesUnsafeFiles() {
        addFile(3, "会员充值.xlsx", "会员充值.xlsx", "充值", 3, "success");
        addFile(-1, "残留.xlsx", "会员充值.xlsx", "充值", 3, "success"); // 待归类
        addFile(3, "评测.xlsx", "eval://orders", "orders", 100, "success"); // 伪路径
        addFile(4, "别类.xlsx", "会员充值.xlsx", "充值", 3, "success"); // 其他分类
        addFile(3, "已删.xlsx", "不存在.xlsx", "充值", 3, "success"); // 文件不存在

        node.apply(state, CTX);

        String abs = workspace.resolve("会员充值.xlsx").toString().replace('\\', '/');
        assertThat(state.getAllowedFilePaths()).containsExactly(abs);
        assertThat(state.getFileCatalog()).contains("会员充值.xlsx").doesNotContain("残留", "评测", "别类", "已删");
    }

    @Test
    @DisplayName("按 categoryId 过滤：不同分类只看到自己的文件")
    void isolatesByCategory() throws Exception {
        Path other = workspace.resolve("其他分类.xlsx");
        Files.copy(new ClassPathResource("test-excel/会员充值.xlsx").getInputStream(), other);
        addFile(3, "会员充值.xlsx", "会员充值.xlsx", "充值", 3, "success");
        addFile(4, "其他分类.xlsx", "其他分类.xlsx", "充值", 3, "success");

        com.gdou.agent.infra.holder.SessionHolder.setCategory(3);
        try {
            node.apply(state, CTX);
        } finally {
            com.gdou.agent.infra.holder.SessionHolder.remove();
        }

        List<String> allowed = state.getAllowedFilePaths();
        assertThat(allowed).hasSize(1);
        assertThat(allowed.get(0)).endsWith("会员充值.xlsx");
    }
}
