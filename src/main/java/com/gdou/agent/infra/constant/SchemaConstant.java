package com.gdou.agent.infra.constant;

import java.util.Set;

/**
 * 库内系统表清单，schema_retrieve 与 sql_guard 共用同一份。
 *
 * 必须按「白名单 in (...)」使用，不得改写成「not in (系统表)」的黑名单：
 * 黑名单只在库里恰好只有系统表时成立，一旦库内多出任何表（历史造数、评测残留），
 * 就会被当成可用业务表放行或注入提示词。2026-11-27 已踩过一次。
 *
 * 新增系统表只改这一处；用户上传的 Excel 不在此列，它们由 read_xlsx + 路径白名单控制。
 */
public class SchemaConstant {

    public static final Set<String> SYSTEM_TABLES = Set.of(
            "session", "message", "category", "file_view",
            "evaluation_run", "eval_case_result", "trace_span");
}
