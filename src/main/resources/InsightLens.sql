-- =============================================================================
-- InsightLens · 应用元数据库 DDL（DuckDB 方言）
-- =============================================================================
-- 用途：存"应用自己产生的数据"——会话、上传批次、评测结果、Trace。
--       不存任何业务明细（业务明细在分析数据源的 .duckdb 文件里）。
-- 引擎：DuckDB 单文件（实际库：data/app.duckdb）
-- 配套：data-architecture.md §2.1（表清单，字段与本文件严格一一对应）
--
-- 字段来源：按设计表（data-architecture.md §2.1）逐字段落地，共 6 张表。
--
-- 【唯一一处刻意偏离设计表】
--   trace_span 的 start / end 落地为 start_at / end_at。
--   原因：END 是 SQL 保留字（CASE ... END），直接当列名建表会报语法错误；
--         START 虽非保留字，但与 END 成对使用，只给一半加双引号会导致
--         每条查询都要写 "end"，忘记引号就报错，可读性也差。
--   若坚持用原名，只能把两列都写成带引号的 "start" / "end"，每次查询都要带引号。
--
-- 【DuckDB 方言要点】
--   1. 没有 AUTO_INCREMENT：自增 = CREATE SEQUENCE + 列 DEFAULT nextval('seq')。
--      序列是独立对象，DROP TABLE 不会删它；要归零须显式 ALTER SEQUENCE ... RESTART 1。
--   2. 取回自增 id 用 `INSERT ... RETURNING id`，不是 getGeneratedKeys()。
--   3. 不用反引号，用双引号或不加引号；内联 COMMENT 无效，须用 COMMENT ON。
--
-- 【分批建表】
--   组 A（session / message）        → I1 后半段（会话从内存改为持久化时）
--   组 B（其余四张）                  → 对应迭代临近时再建，提前建只增加维护负担
-- =============================================================================


-- =============================================================================
-- 组 A：会话持久化（I1 后半段 ~ I2）
-- =============================================================================

CREATE SEQUENCE IF NOT EXISTS seq_category_id START 1;

CREATE TABLE IF NOT EXISTS category(
                                       id          INTEGER PRIMARY KEY DEFAULT nextval('seq_category_id'),
    name        VARCHAR NOT NULL UNIQUE,   -- 分类名 = workspace 下的文件夹名，如 电商运营
    created_at  TIMESTAMP NOT NULL
    );
COMMENT ON TABLE  category         IS '大分类：隔离单元，一个分类 = 一个文件夹 = 一个对话窗口';
COMMENT ON COLUMN category.name    IS '分类名，唯一；对应 agent.workspace 下的目录名';
COMMENT ON COLUMN category.created_at IS '创建时间';

CREATE SEQUENCE IF NOT EXISTS seq_file_view_id START 1;

CREATE TABLE IF NOT EXISTS file_view(
                                        id           INTEGER PRIMARY KEY DEFAULT nextval('seq_file_view_id'),
    category_id  INTEGER NOT NULL DEFAULT -1, -- 所属大分类；-1=待归类（解析阶段），LLM 归类后回填真实 id
    upload_id    VARCHAR NOT NULL,       -- 文件级 UUID：一个文件一个，多 sheet 共享
    orig_name    VARCHAR NOT NULL,       -- 原始文件名（含版本+日期后缀），前端报表展示
    file_path    VARCHAR NOT NULL,       -- 相对 agent.workspace 的路径，如 <uuid>/<name>.xlsx
    sheet_name   VARCHAR NOT NULL,       -- Excel 内的工作表名，可中文，仅展示与映射
    row_count    BIGINT,                 -- 本 sheet 行数，回答回显"基于 N 行"
    sample_data  VARCHAR,                -- 首行样本 {sheet={列=值}}，供上传归类提示词按内容细分
    status       VARCHAR NOT NULL,       -- success / failed
    created_at   TIMESTAMP NOT NULL      -- 上传时间，前端报表的时间轴
    );
-- 老库升级：已建好的库不会因 CREATE TABLE IF NOT EXISTS 自动加列，需单独补这一句
ALTER TABLE file_view ADD COLUMN IF NOT EXISTS sample_data VARCHAR;
-- 待归类文件统一写 category_id = -1（哨兵值），列本身保持 NOT NULL：
-- 不需要 ALTER COLUMN 改约束，也就不会撞上 DuckDB「有索引依赖不能 ALTER」的限制。

COMMENT ON TABLE file_view IS '文件注册表：一行=一个sheet；文件是唯一数据源，数据行不入库（仅存首行样本）';
COMMENT ON COLUMN file_view.category_id  IS '所属大分类，逻辑关联 category.id；三节点按此过滤实现隔离；-1 表示待归类';
COMMENT ON COLUMN file_view.upload_id    IS '文件级UUID，删除与文件列表聚合按此定位；orig_name 仅展示不做删除条件';
COMMENT ON COLUMN file_view.file_path    IS '相对 agent.workspace 的文件路径，直读 read_xlsx 用；模型SQL里的表名来源';
COMMENT ON COLUMN file_view.sheet_name   IS 'Excel原sheet名；用户口语里说"订单明细"时靠它定位到本行';
COMMENT ON COLUMN file_view.sample_data  IS '首行样本 Map 的 toString，形如 {订单明细={order_id=1001,amount=299}}，供上传提示词第4段使用';
COMMENT ON COLUMN file_view.status       IS 'success / failed；单个sheet解析失败不影响同文件其他sheet';

CREATE INDEX IF NOT EXISTS idx_file_view_category ON file_view (category_id, created_at);
CREATE INDEX IF NOT EXISTS idx_file_view_upload   ON file_view (upload_id);




CREATE SEQUENCE IF NOT EXISTS seq_session_id START 1;

CREATE TABLE IF NOT EXISTS session(
                                      id          INTEGER PRIMARY KEY DEFAULT nextval('seq_session_id'),
    title       VARCHAR NOT NULL,
    category_id INTEGER,   -- 窗口绑定的大分类；逻辑关联 category.id
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
    );
-- 旧库升级：session 表可能早于 category_id 列就已建好，IF NOT EXISTS 建表会跳过、不会补列。
-- 必须先补列再 COMMENT ON，否则老库上注释语句会因列不存在而报错。
ALTER TABLE session ADD COLUMN IF NOT EXISTS category_id INTEGER;
COMMENT ON TABLE  session            IS '用户会话表：一个会话 = 一段多轮对话';
COMMENT ON COLUMN session.id         IS '用户会话id（自增主键）';
COMMENT ON COLUMN session.title      IS '用户会话标题（默认取首条提问前 30 字，可重命名）';
COMMENT ON COLUMN session.created_at IS '用户会话创建时间';
COMMENT ON COLUMN session.updated_at IS '用户会话最后活跃时间（每轮消息后更新，用于会话列表排序）';
COMMENT ON COLUMN session.category_id IS '会话绑定的大分类；为空表示未绑定。Agent 检索范围由此限定';


CREATE SEQUENCE IF NOT EXISTS seq_message_id START 1;

CREATE TABLE IF NOT EXISTS message(
                                      id          INTEGER PRIMARY KEY DEFAULT nextval('seq_message_id'),
    session_id  INTEGER NOT NULL,
    role        VARCHAR NOT NULL,
    content     VARCHAR NOT NULL,
    created_at  TIMESTAMP NOT NULL
    );
COMMENT ON TABLE  message            IS '消息表：会话中的每一轮提问 / 回答 / 工具结果';
COMMENT ON COLUMN message.id         IS '消息id（自增主键）';
COMMENT ON COLUMN message.session_id IS '所属会话id，逻辑关联 session.id（应用层保证，不建外键）';
COMMENT ON COLUMN message.role       IS '角色：user / assistant / tool / system';
COMMENT ON COLUMN message.content    IS '消息正文；tool 角色存工具调用参数与结果摘要（截断至 200 行）';
COMMENT ON COLUMN message.created_at IS '消息产生时间（同一会话内按此升序拼装上下文）';

-- 按会话取最近 N 条是最高频查询，必须建索引
CREATE INDEX IF NOT EXISTS idx_message_session_created
    ON message (session_id, created_at);


-- =============================================================================
-- 组 B-1：文件注册表（I4.5）
-- 现行方案：一个 sheet 一行注册表，数据不入库，直读 xlsx 文件。
-- upload_id 为文件级 UUID，多 sheet 共享；删除与文件列表聚合按此定位。
-- =============================================================================


-- =============================================================================
-- 组 B-2：评测（I7）—— 1 run → N case，一对多
-- =============================================================================

CREATE SEQUENCE IF NOT EXISTS seq_evaluation_run_id START 1;

CREATE TABLE IF NOT EXISTS evaluation_run(
                                             id                     INTEGER PRIMARY KEY DEFAULT nextval('seq_evaluation_run_id'),
    created_at             TIMESTAMP NOT NULL,
    model                  VARCHAR NOT NULL,
    code_commit            VARCHAR,
    total_cases            INTEGER NOT NULL,
    pass_count             INTEGER NOT NULL,
    fail_count             INTEGER NOT NULL,
    accuracy               DOUBLE,
    hallucination_count    INTEGER DEFAULT 0,
    write_op_blocked_count INTEGER DEFAULT 0,
    avg_latency_ms         DOUBLE,
    p95_latency_ms         DOUBLE,
    total_cost_yuan        DECIMAL(10, 4),
    notes                  VARCHAR
    );
COMMENT ON TABLE  evaluation_run                        IS '评测跑批汇总表：一次跑批一行，回答"这次正确率多少"';
COMMENT ON COLUMN evaluation_run.id                     IS '跑批id（自增主键）';
COMMENT ON COLUMN evaluation_run.created_at             IS '跑批开始时间';
COMMENT ON COLUMN evaluation_run.model                  IS '本次使用的模型标识，如 deepseek-v4-flash';
COMMENT ON COLUMN evaluation_run.code_commit            IS '代码版本（git commit 短哈希），用于定位"改了哪段代码导致正确率变化"';
COMMENT ON COLUMN evaluation_run.total_cases            IS '评测集总题数';
COMMENT ON COLUMN evaluation_run.pass_count             IS '通过题数（execution_match = true 的条数）';
COMMENT ON COLUMN evaluation_run.fail_count             IS '失败题数';
COMMENT ON COLUMN evaluation_run.accuracy               IS '正确率 = pass_count / total_cases，验收线 >= 0.80';
COMMENT ON COLUMN evaluation_run.hallucination_count    IS '编造数字次数，验收线必须 = 0';
COMMENT ON COLUMN evaluation_run.write_op_blocked_count IS '写操作拦截成功次数，应等于评测集中写操作样本数（拦截率 100%）';
COMMENT ON COLUMN evaluation_run.avg_latency_ms         IS '平均端到端延迟（毫秒）';
COMMENT ON COLUMN evaluation_run.p95_latency_ms         IS 'P95 延迟（毫秒），验收线 < 30000';
COMMENT ON COLUMN evaluation_run.total_cost_yuan        IS '本次跑批模型调用总成本（元），单次成本验收线 < 0.1';
COMMENT ON COLUMN evaluation_run.notes                  IS '备注，如"换 DuckDB 方言后首跑"';


CREATE SEQUENCE IF NOT EXISTS seq_eval_case_result_id START 1;

CREATE TABLE IF NOT EXISTS eval_case_result(
                                               id               INTEGER PRIMARY KEY DEFAULT nextval('seq_eval_case_result_id'),
    run_id           INTEGER NOT NULL,
    case_id          VARCHAR NOT NULL,
    question         VARCHAR,
    difficulty       VARCHAR,
    generated_sql    VARCHAR,
    gold_sql         VARCHAR,
    execution_match  BOOLEAN,
    is_hallucination BOOLEAN DEFAULT FALSE,
    write_op_blocked BOOLEAN DEFAULT FALSE,
    latency_ms       DOUBLE,
    tokens_used      INTEGER,
    error_type       VARCHAR
    );
COMMENT ON TABLE  eval_case_result                  IS '单条评测明细表：一道题一行，回答"哪题挂了、为什么挂"，积累成回归问题清单';
COMMENT ON COLUMN eval_case_result.id               IS '明细id（自增主键）';
COMMENT ON COLUMN eval_case_result.run_id           IS '所属跑批id，逻辑关联 evaluation_run.id';
COMMENT ON COLUMN eval_case_result.case_id          IS '对应评测集里的题目编号，如 T1-01 / T5-03';
COMMENT ON COLUMN eval_case_result.question         IS '原始提问文本';
COMMENT ON COLUMN eval_case_result.difficulty       IS '难度分档：easy / medium / hard';
COMMENT ON COLUMN eval_case_result.generated_sql    IS 'Agent 生成的 SQL（原文保留，用于复盘）';
COMMENT ON COLUMN eval_case_result.gold_sql         IS '标准答案 SQL';
COMMENT ON COLUMN eval_case_result.execution_match  IS '两者执行结果是否一致（评分主依据）';
COMMENT ON COLUMN eval_case_result.is_hallucination IS '该题是否出现编造数字（结论与执行结果不符）';
COMMENT ON COLUMN eval_case_result.write_op_blocked IS '该题若为写操作，是否被 SqlGuard 成功拦截';
COMMENT ON COLUMN eval_case_result.latency_ms       IS '该题端到端耗时（毫秒）';
COMMENT ON COLUMN eval_case_result.tokens_used      IS '该题消耗 token 总数（含多轮调用）';
COMMENT ON COLUMN eval_case_result.error_type       IS '失败归类：schema_wrong / sql_syntax / semantic_wrong / guard_reject / timeout；通过则留空';

CREATE INDEX IF NOT EXISTS idx_eval_case_run
    ON eval_case_result (run_id, case_id);


-- =============================================================================
-- 组 B-3：可观测（I6）
-- 说明：接入 Langfuse 时 Trace 由 Langfuse 托管，本表可不建；自建轻量 Trace 才需要，二选一。
-- 注意：start_at / end_at 对应设计表中的 start / end（END 为 SQL 保留字，见文件头说明）。
-- =============================================================================

CREATE SEQUENCE IF NOT EXISTS seq_trace_span_id START 1;

CREATE TABLE IF NOT EXISTS trace_span(
                                         id        INTEGER PRIMARY KEY DEFAULT nextval('seq_trace_span_id'),
    trace_id  VARCHAR NOT NULL,
    node      VARCHAR NOT NULL,
    start_at  TIMESTAMP NOT NULL,
    end_at    TIMESTAMP,
    attrs     JSON
    );
COMMENT ON TABLE  trace_span          IS '可观测链路表：一次请求的一个节点执行一行（接 Langfuse 时可不建）';
COMMENT ON COLUMN trace_span.id        IS 'span记录id（自增主键）';
COMMENT ON COLUMN trace_span.trace_id  IS '整条链路的唯一标识，同一请求的所有 span 共享';
COMMENT ON COLUMN trace_span.node      IS '节点名：clarify / schema_retrieve / sql_generate / sql_guard / sql_execute / judge / summarize';
COMMENT ON COLUMN trace_span.start_at  IS '节点开始时间（设计表原名 start）';
COMMENT ON COLUMN trace_span.end_at    IS '节点结束时间（设计表原名 end）；为空表示仍在执行或异常中断';
COMMENT ON COLUMN trace_span.attrs     IS '节点属性 JSON：模型名、token 数、生成的 SQL、错误信息等（字段约定见 trace-convention.md）';

CREATE INDEX IF NOT EXISTS idx_trace_span_trace
    ON trace_span (trace_id, start_at);


-- =============================================================================
-- 验收：建完后跑以下查询确认表、字段、注释都在
-- =============================================================================
-- 1) 6 张表是否都在
--    SELECT table_name FROM duckdb_tables() WHERE schema_name = 'main' ORDER BY table_name;
-- 2) 字段与注释（duckdb_columns() 的 comment 列存的就是 COMMENT ON COLUMN 的内容）
--    SELECT table_name, column_name, data_type, comment
--      FROM duckdb_columns() WHERE schema_name = 'main'
--     ORDER BY table_name, column_index;
-- 3) 表注释（不同 DuckDB 版本列名略有差异，不确定就先 DESCRIBE 一下）
--    SELECT table_name, comment FROM duckdb_tables() WHERE schema_name = 'main';
-- 4) 自增是否生效（两条 id 应为 1、2；若为 NULL 说明序列没接上）
--    INSERT INTO session(title, created_at, updated_at) VALUES ('测试会话A', now(), now());
--    INSERT INTO session(title, created_at, updated_at) VALUES ('测试会话B', now(), now());
--    SELECT id, title FROM session ORDER BY id;
-- 5) JSON 列可写性
--    INSERT INTO trace_span(trace_id, node, start_at, attrs)
--      VALUES ('t-1', 'clarify', now(), '{"model":"deepseek-v4-flash","tokens":812}');
--    SELECT id, attrs->>'model' AS model FROM trace_span;
