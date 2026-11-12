package com.gdou.agent.orchestration.node.impl;

import com.gdou.agent.orchestration.AnalysisState;
import com.gdou.agent.orchestration.RunContext;
import com.gdou.agent.orchestration.node.Node;
import com.gdou.agent.orchestration.node.NodeName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service(NodeName.SQL_EXECUTE)
public class SqlExecuteNode implements Node {
    // 节点依赖统一走字段注入：后续新增 Service 只需加一个 @Autowired 字段，不必改构造参数列表
    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void apply(AnalysisState state, RunContext ctx) {
        long start = System.currentTimeMillis();
        String sql = state.getSql();
        log.info("[sql-execute] >>> sql={}", sql);
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
            log.info("[sql-execute] <<< cost={}ms, rowCount={}", System.currentTimeMillis() - start, res.size());
            if (res.isEmpty()) {
                log.info("[sql-execute] 结果为空，交由 judge 判定 empty，不在此处中断");
            }
        } catch (Exception e) {
            log.error("[sql-execute] 执行失败 cost={}ms, sql={}", System.currentTimeMillis() - start, sql, e);
            state.setError(e.getMessage());
            state.setVerdict("error");
            throw new RuntimeException(e);
        }
    }
}
