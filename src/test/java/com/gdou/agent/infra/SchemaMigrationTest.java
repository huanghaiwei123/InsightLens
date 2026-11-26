package com.gdou.agent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建表脚本回归：上传流程先把 Excel 元数据落库、再让模型归类并回填 category_id，
 * 因此解析阶段必须给 category_id 一个合法值。现行方案统一写哨兵值 -1，
 * 列本身保持 NOT NULL，避免用 ALTER COLUMN 改约束时撞上 DuckDB 的索引依赖限制。
 */
class SchemaMigrationTest {

    private static String schema() throws Exception {
        try (InputStream in = SchemaMigrationTest.class.getResourceAsStream("/InsightLens.sql")) {
            assertThat(in).as("classpath:InsightLens.sql 必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void insertPendingFile(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO file_view(category_id, upload_id, orig_name, file_path, sheet_name, status, created_at)
                    VALUES (-1, 'u1', 'a.xlsx', 'temp/a.xlsx', 'Sheet1', 'success', current_timestamp)
                    """);
            try (ResultSet rs = st.executeQuery(
                    "SELECT category_id FROM file_view WHERE upload_id = 'u1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("待归类文件的哨兵值").isEqualTo(-1);
            }
        }
    }

    @Test
    @DisplayName("新库：建表脚本执行后 category_id 默认 -1，显式写 -1 也能插入")
    void freshSchemaUsesPendingSentinel() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute(schema());
                // DDL 自带 DEFAULT -1，即使调用方漏写也能落库
                st.executeUpdate("""
                        INSERT INTO file_view(upload_id, orig_name, file_path, sheet_name, status, created_at)
                        VALUES ('u2', 'b.xlsx', 'temp/b.xlsx', 'Sheet1', 'success', current_timestamp)
                        """);
            }
            insertPendingFile(conn);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT category_id FROM file_view WHERE upload_id = 'u2'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("DDL 默认值").isEqualTo(-1);
            }
        }
    }

    @Test
    @DisplayName("老库：NOT NULL + 已有索引的库升级后，写 -1 可插入且不需要 ALTER 约束")
    void legacySchemaAcceptsPendingSentinel() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE file_view(
                            id INTEGER, category_id INTEGER NOT NULL, upload_id VARCHAR,
                            orig_name VARCHAR, file_path VARCHAR, sheet_name VARCHAR,
                            status VARCHAR, created_at TIMESTAMP
                        )
                        """);
                // 真实老库上这两个索引已存在；如果脚本里还留着 ALTER COLUMN，
                // DuckDB 会因索引依赖直接报错，这个用例就是用来守住这条回归的。
                st.execute("CREATE INDEX idx_file_view_category ON file_view(category_id, created_at)");
                st.execute("CREATE INDEX idx_file_view_upload ON file_view(upload_id)");
                st.execute(schema());
            }
            insertPendingFile(conn);
        }
    }
}
