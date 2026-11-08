package com.gdou.orchestration.node.impl;

import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.RunContext;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service(NodeName.SQL_EXECUTE)
public class SqlExecuteNode implements Node {
    private final JdbcTemplate jdbc;
    public SqlExecuteNode(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        String sql = state.getSql();
        try {
            List<Object> res = jdbc.query(sql, (rs, rowNum) -> {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= count; i++) {
                    columns.add(meta.getColumnName(i));
                }
                Map<String, String> data = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    String column = columns.get(i);
                    String value = rs.getString(column);
                    data.put(column, value);
                }
                return data;
            });
            state.setResultRows(res);
            state.setRowCount(res.size());
        } catch (Exception e) {
            state.setError(e.getMessage());
            state.setVerdict("error");
            throw new RuntimeException(e);
        }

    }
}
