package com.gdou.agent.orchestration.node.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.llm.LlmClient;
import com.gdou.agent.infra.stream.NoOpStreamSink;
import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ② Schema 检索节点：验证「喂给模型的是本分类真实 Excel 的路径 + 列结构」，
 * 以及路径白名单只包含安全文件（-1 待归类、eval:// 伪路径、已删除文件都排除）。
 */
class SchemaRetrieveNodeTest {

    private static final RunContext CTX = new RunContext("schema-test", new NoOpStreamSink());

    private Connection duckConn;
    private JdbcTemplate jdbc;
    private SchemaRetrieveNode node;
    private LlmClient llmClient;
    private Path workspace;
    private AnalysisState state;

    /** 默认评估结果：列齐全。个别用例覆盖成 partial / 不合法返回 */
    private static final String DEFAULT_ASSESSMENT =
            "{\"sufficiency\":\"sufficient\",\"missing\":[],"
                    + "\"query_plan\":\"读取「会员充值.xlsx」的「充值」sheet，按「会员等级」汇总「充值金额」\","
                    + "\"limitation\":\"\"}";

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
        llmClient = mock(LlmClient.class);
        // 评估每次都会调用模型，默认给一个「可满足」结果；需要其他结论的用例自行覆盖
        when(llmClient.call(anyList())).thenReturn(DEFAULT_ASSESSMENT);
        ReflectionTestUtils.setField(node, "jdbc", jdbc);
        ReflectionTestUtils.setField(node, "llmClient", llmClient);
        ReflectionTestUtils.setField(node, "objectMapper", new ObjectMapper());
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

    @Test
    @DisplayName("拿到列结构后评估数据可用性：结论/缺失项/查询建议/用户说明写入 state")
    void assessesDataCapability() {
        addFile(3, "会员充值.xlsx", "会员充值.xlsx", "充值", 3, "success");
        state.setClarifiedQuestion("会员等级里最高级别是哪个");
        when(llmClient.call(anyList())).thenReturn(
                "{\"sufficiency\":\"partial\","
                        + "\"missing\":[\"缺少「级别」列，无法按级别数值精确排序\"],"
                        + "\"query_plan\":\"按会员等级名称的业务顺序排序\","
                        + "\"limitation\":\"表里只有等级名称，没有级别数值\"}");

        node.apply(state, CTX);

        assertThat(state.getDataSufficiency()).isEqualTo("partial");
        assertThat(state.getDataLimitations()).containsExactly("缺少「级别」列，无法按级别数值精确排序");
        assertThat(state.getQueryPlan()).contains("业务顺序");
        assertThat(state.getDataLimitationNote()).contains("只有等级名称");

        // 评估必须看到真实列与问题，否则等于凭常识猜
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).call(captor.capture());
        String userMsg = ((UserMessage) captor.getValue().get(1)).getText();
        assertThat(userMsg).contains("会员等级里最高级别是哪个").contains("会员等级", "充值金额");
    }

    @Test
    @DisplayName("评估返回不是 JSON 时降级为未评估：不抛异常，文件清单照常产出")
    void degradesWhenAssessmentFails() {
        addFile(3, "会员充值.xlsx", "会员充值.xlsx", "充值", 3, "success");
        when(llmClient.call(anyList())).thenReturn("抱歉，我无法判断");

        node.apply(state, CTX);

        assertThat(state.getDataSufficiency()).isNull();
        assertThat(state.getDataLimitations()).isNull();
        assertThat(state.getFileCatalog()).contains("会员充值.xlsx");
        assertThat(state.getAllowedFilePaths()).hasSize(1);
    }

    @Test
    @DisplayName("本分类没有文件时直接判 insufficient，不浪费一次模型调用")
    void noFileMeansInsufficient() {
        node.apply(state, CTX);

        assertThat(state.getDataSufficiency()).isEqualTo("insufficient");
        assertThat(state.getDataLimitations()).containsExactly("当前分类下没有可读的表格文件");
        verifyNoInteractions(llmClient);
    }
}
