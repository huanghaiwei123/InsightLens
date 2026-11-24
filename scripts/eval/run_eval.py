"""
最小评测 runner：把「手动点 6 次接口、肉眼看答案」变成自动跑批 + 按结果集判分。

为什么分两段：
  collect  在线：应用运行时逐题调 /personal/chat/stream（SSE），记录答案、耗时，
                 并从应用日志里提取本轮实际执行的 SQL。
  score    离线：应用停止后打开 data/app.duckdb，分别执行 gold_sql 和
                 generated_sql，比对结果集，出正确率报告。

必须离线的原因：DuckDB 是单写者，应用持有写连接时 Python 侧不能同时开库。
generated_sql 从日志来的原因：/chat/overAll 只返回 GraphReturner{answer}，
不吐 SQL 和结果集；SqlExecuteNode 会把最终执行的 SQL 打进
「[sql-execute] >>> sql=」，且提示词要求单行 SQL，可以稳定提取。

用法（仓库根目录）：
  1) 启动应用并把日志落盘。必须同时满足两点：
     - UTF-8 编码，否则中文别名乱码会被误判 sql_error；
     - 注入与评测集 eval_date 一致的基准日，否则「上周 / 近 30 天」相对时间会漂移。
       （JVM 系统属性由 ClarifyNode 的 insightlens.eval-base-date 读取，生产留空走今天）
       mvn -Dfile.encoding=UTF-8 spring-boot:run ^
         "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dinsightlens.eval-base-date=2026-11-11" ^
         "-Dspring-boot.run.arguments=--spring.ai.mcp.client.enabled=false" > app.log 2>&1
  2) 在线采集（SSE 端点需先建 session，脚本内部自动完成）：
       python scripts/eval/run_eval.py collect --log-file app.log
  3) 停掉应用，离线判分：
       python scripts/eval/run_eval.py score
  4) 无需应用、只想验证判分逻辑与库是否可用：
       python scripts/eval/run_eval.py score --self-check

升级路径：以后若加一个评测专用端点，让它返回 generated_sql / resultRows，
只需替换 collect 里取 SQL 的那一段，score 与报告格式一行都不用改。
"""

import argparse
import json
import re
import statistics
import time
import urllib.parse
import urllib.request
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

import duckdb

SQL_LOG = re.compile(r"\[sql-execute\] >>> sql=(.+)")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def out_dir() -> Path:
    p = repo_root() / "scripts" / "eval" / "out"
    p.mkdir(parents=True, exist_ok=True)
    return p


def resolve_assets(explicit: str | None) -> Path:
    import os

    if explicit:
        p = Path(explicit)
        if not p.is_dir():
            raise SystemExit(f"--assets 目录不存在: {p}")
        return p
    env = os.environ.get("DATA_AGENT_ASSETS")
    if env and Path(env).is_dir():
        return Path(env)
    candidates = [
        repo_root() / "examples" / "ecommerce",
        Path("C:/Users/huanghaiwei/WorkBuddy/2026-10-18-15-54-07/data-agent/examples/ecommerce"),
    ]
    for p in candidates:
        if p.is_dir():
            return p
    raise SystemExit("找不到示例域资产目录，请用 --assets 指定")


def load_cases(assets: Path) -> list[dict]:
    path = assets / "eval_set.jsonl"
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    return cases


def http_opener():
    # 环境里常设了 http_proxy，localhost 请求必须显式绕过，否则会被代理拦成 502
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def create_session(base_url: str, timeout: float) -> int:
    """流式端点要求 sessionId；每次评测题新建独立窗口，避免题目间上下文串味。"""
    url = base_url.rstrip("/") + "/personal/create"
    req = urllib.request.Request(url, method="GET")
    with http_opener().open(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    session_id = payload.get("data")
    if session_id is None:
        raise RuntimeError(f"create 未返回 sessionId: {payload}")
    return int(session_id)


def post_question(base_url: str, question: str, session_id: int,
                  timeout: float) -> tuple[int, dict]:
    """调 /personal/chat/stream，把 SSE 帧累积成一个等价于旧接口的 dict。

    答案来源：SseStreamSink 只推 token 帧（done 帧不带 answer，避免重复），
    所以按 SSE 规范逐行读 event:/data:，把 token 帧的 text 拼起来。
    返回 dict 保持 answer / questionToUser / error 三个键，score 阶段无需改动。
    """
    url = base_url.rstrip("/") + "/personal/chat/stream"
    query = urllib.parse.urlencode({"userMsg": question, "sessionId": session_id})
    req = urllib.request.Request(
        url + "?" + query,
        headers={"Accept": "text/event-stream"},
        method="POST",
    )
    answer_parts: list[str] = []
    errors: list[str] = []
    event_name = ""
    with http_opener().open(req, timeout=timeout) as resp:
        status = resp.status
        for raw_line in resp:
            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
            if line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                data_text = line[5:].strip()
                if not data_text:
                    continue
                try:
                    frame = json.loads(data_text)
                except json.JSONDecodeError:
                    continue
                if event_name == "token" and frame.get("text"):
                    answer_parts.append(frame["text"])
                elif event_name == "error" and frame.get("message"):
                    errors.append(frame["message"])
                elif event_name == "done":
                    break
    answer = "".join(answer_parts) or None
    return status, {"answer": answer, "questionToUser": None,
                    "error": "; ".join(errors) if errors else None}


def decode_log(chunk: bytes) -> str:
    """日志编码由 JVM 默认字符集决定：Windows 常见 GBK，带 -Dfile.encoding=UTF-8 时是 UTF-8。

    必须按 UTF-8 严格解码再回退 GBK，不能一上来就 errors="replace"：
    中文别名（如 as 客单价）被替换成 U+FFFD 后，抓出来的 SQL 在 DuckDB 里
    会报 Binder Error，题目被误判成 sql_error，而应用实际执行的 SQL 是对的。
    """
    try:
        return chunk.decode("utf-8")
    except UnicodeDecodeError:
        pass
    try:
        return chunk.decode("gbk")
    except UnicodeDecodeError:
        return chunk.decode("utf-8", errors="replace")


def log_tail(path: Path, offset: int) -> tuple[str, int]:
    if not path.is_file():
        return "", offset
    with open(path, "rb") as f:
        f.seek(offset)
        chunk = f.read()
        return decode_log(chunk), f.tell()


def extract_sql(log_text: str) -> str | None:
    matches = SQL_LOG.findall(log_text)
    return matches[-1].strip() if matches else None


def cmd_collect(args) -> None:
    assets = resolve_assets(args.assets)
    cases = load_cases(assets)
    log_path = Path(args.log_file).resolve() if args.log_file else None
    collection = out_dir() / "collection.jsonl"

    print(f"[collect] 目标 {args.base_url}，{len(cases)} 题")
    eval_dates = sorted({c.get("eval_date") for c in cases if c.get("eval_date")})
    if eval_dates:
        print(f"[collect] 评测集基准日 {eval_dates}；请确认应用以 "
              f"-Dinsightlens.eval-base-date=<基准日> 启动，否则相对时间题会漂移")
    if log_path:
        print(f"[collect] 日志文件 {log_path}")
    else:
        print("[collect] 未提供 --log-file：只采集答案，score 阶段无法判 execution_match")

    results = []
    for case in cases:
        offset = 0
        if log_path and log_path.is_file():
            _, offset = log_tail(log_path, 0)
        started = time.time()
        status, payload, err = 0, {}, None
        try:
            session_id = create_session(args.base_url, args.timeout)
            status, payload = post_question(args.base_url, case["question"],
                                            session_id, args.timeout)
            # SSE 的 error 帧说明链路失败，即便 HTTP 200 也不能算成功采集
            if payload.get("error"):
                err = payload["error"]
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
        latency = round((time.time() - started) * 1000, 1)

        # 等日志缓冲区刷出，再取本轮新增内容
        if log_path:
            time.sleep(args.settle)
        new_text, offset = log_tail(log_path, offset) if log_path else ("", 0)
        generated_sql = extract_sql(new_text)
        # 修正循环会打多条 sql 日志，最后一条才是最终执行的那一条

        # post_question 已把 SSE 帧拍平成扁平 dict（旧接口是 Result{data:{...}}，别混）
        record = {
            "case_id": case["id"],
            "difficulty": case["difficulty"],
            "question": case["question"],
            "answer": payload.get("answer"),
            "questionToUser": payload.get("questionToUser"),
            "generated_sql": generated_sql,
            "latency_ms": latency,
            "http_status": status,
            "error": err,
        }
        results.append(record)
        flag = "OK" if status == 200 and not err else "FAIL"
        print(
            f"  {case['id']} [{flag}] {latency:>8.1f}ms  "
            f"sql={'有' if generated_sql else '无'}  answer={(payload.get('answer') or '')[:28]}"
        )

    with open(collection, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[collect] 已写入 {collection}")


def normalize(rows) -> list[tuple[str, ...]]:
    """把结果集转成可比较的字符串元组：忽略列名，保留行顺序，数值统一到 6 位小数。"""
    out = []
    for row in rows:
        cells = []
        for v in row:
            if v is None:
                cells.append("<NULL>")
            elif isinstance(v, Decimal):
                cells.append(f"{float(v):.6f}")
            elif isinstance(v, bool):
                cells.append("1" if v else "0")
            elif isinstance(v, float):
                cells.append(f"{v:.6f}")
            elif isinstance(v, (datetime, date)):
                cells.append(v.isoformat())
            else:
                cells.append(str(v).strip())
        out.append(tuple(cells))
    return out


def result_sets_match(expected, actual) -> bool:
    """按列值多重集合比对：允许 generated SQL 多返回列、列顺序不同、列名不同。

    模型常把别名写成中文口径说明（如「客单价=GMV/用户数」），或顺手多带一列序号；
    这些都不改变 gold_sql 的答案语义。只要 gold 的每一列都能在 actual 中找到
    一列值完全一致（含重复值、NULL），就判通过。行顺序无关。
    """
    if len(expected) != len(actual):
        return False
    if not expected:
        return True
    gold_cols = len(expected[0])
    actual_cols = len(actual[0])
    if actual_cols < gold_cols:
        return False

    def assign(i: int, used: set[int]) -> bool:
        if i == gold_cols:
            return True
        gold_values = sorted(row[i] for row in expected)
        for j in range(actual_cols):
            if j in used:
                continue
            if sorted(row[j] for row in actual) == gold_values:
                # 回溯：列值重复时贪心可能占掉后面唯一能匹配的列
                if assign(i + 1, used | {j}):
                    return True
        return False

    return assign(0, set())


def run_sql(con, sql: str):
    return normalize(con.execute(sql).fetchall())


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, int(len(ordered) * p + 0.5) - 1))
    return round(ordered[idx], 1)


def cmd_score(args) -> None:
    assets = resolve_assets(args.assets)
    cases = {c["id"]: c for c in load_cases(assets)}
    db_path = Path(args.db) if args.db else repo_root() / "data" / "app.duckdb"

    if args.self_check:
        records = [
            {
                "case_id": c["id"],
                "question": c["question"],
                "difficulty": c["difficulty"],
                "generated_sql": c["gold_sql"],
                "answer": "(self-check)",
                "latency_ms": 0.0,
                "error": None,
            }
            for c in cases.values()
        ]
        print("[score] self-check 模式：用 gold_sql 冒充 generated_sql，期望全部通过")
    else:
        collection = out_dir() / "collection.jsonl"
        if not collection.is_file():
            raise SystemExit(f"缺少 {collection}，请先执行 collect")
        records = [
            json.loads(line)
            for line in collection.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    try:
        con = duckdb.connect(str(db_path), read_only=True)
    except Exception as e:
        raise SystemExit(
            f"打不开 {db_path}：{e}\n"
            "DuckDB 是单写者，请先停掉应用再执行 score（或先跑 --self-check）。"
        ) from e

    details = []
    try:
        for rec in records:
            case = cases.get(rec["case_id"])
            if case is None:
                continue
            row = {
                "case_id": rec["case_id"],
                "difficulty": rec.get("difficulty"),
                "question": rec["question"],
                "generated_sql": rec.get("generated_sql"),
                "gold_sql": case["gold_sql"],
                "execution_match": False,
                "latency_ms": rec.get("latency_ms", 0.0),
                "answer": rec.get("answer"),
                "error_type": None,
            }
            try:
                expected = run_sql(con, case["gold_sql"])
            except Exception as e:
                row["error_type"] = "gold_error"
                row["detail"] = str(e)
                details.append(row)
                continue
            generated = rec.get("generated_sql")
            if not generated:
                row["error_type"] = "no_sql"
                details.append(row)
                continue
            try:
                actual = run_sql(con, generated)
            except Exception as e:
                row["error_type"] = "sql_error"
                row["detail"] = str(e)
                details.append(row)
                continue
            # 主判分按「列值内容」：并列 ORDER BY 的行序、模型多带的列都不改变答案语义。
            # 严格的逐列逐行比较单独记 order_match 供诊断。
            row["execution_match"] = result_sets_match(expected, actual)
            row["order_match"] = actual == expected
            if not row["execution_match"]:
                row["error_type"] = "mismatch"
                row["detail"] = (
                    f"expected {len(expected)} rows x {len(expected[0]) if expected else 0} cols / "
                    f"actual {len(actual)} rows x {len(actual[0]) if actual else 0} cols; "
                    f"head expected={expected[:2]} actual={actual[:2]}"
                )
            details.append(row)
    finally:
        con.close()

    total = len(details)
    passed = sum(1 for d in details if d["execution_match"])
    latencies = [d["latency_ms"] for d in details if d["latency_ms"]]
    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "eval_date": next(iter(cases.values())).get("eval_date"),
        "model": None,
        "total_cases": total,
        "pass_count": passed,
        "fail_count": total - passed,
        "accuracy": round(passed / total, 4) if total else 0.0,
        "avg_latency_ms": round(statistics.mean(latencies), 1) if latencies else None,
        "p95_latency_ms": percentile(latencies, 0.95),
        "notes": (
            "self-check" if args.self_check
            else "execution_match：按列值多重集合比对，忽略列名/列顺序、允许 generated 多列；"
                 "行顺序单独记 order_match"
        ),
    }
    report = {"summary": summary, "cases": details}
    report_path = out_dir() / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== 评测结果 ===")
    for d in details:
        mark = "PASS" if d["execution_match"] else "FAIL"
        print(f"  {d['case_id']} [{mark}] {d['error_type'] or ''} {d.get('detail', '')}")
    print(
        f"\n正确率 {passed}/{total} = {summary['accuracy']:.0%}  "
        f"P95={summary['p95_latency_ms']}ms  "
        f"验收线 ≥80% / P95<30000ms"
    )
    print(f"报告: {report_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="最小评测 runner")
    sub = parser.add_subparsers(dest="command", required=True)

    p_collect = sub.add_parser("collect", help="在线逐题调接口并采集 SQL")
    p_collect.add_argument("--base-url", default="http://127.0.0.1:8080")
    p_collect.add_argument("--log-file", default=None, help="应用日志文件，用于提取 generated_sql")
    p_collect.add_argument("--assets", default=None)
    # 链路是三次串行模型调用，中转站抖动时单题可到 130s。120s 会在应用仍运行时
    # 掐断连接，日志随后才落盘 → 已生成的 SQL 被记成 no_sql，P95 也被截成假的 120s。
    p_collect.add_argument("--timeout", type=float, default=300.0,
                           help="单题等待上限，需大于实际链路耗时，否则 SQL 会被误判为 no_sql")
    p_collect.add_argument("--settle", type=float, default=0.5, help="等待日志刷出的秒数")
    p_collect.set_defaults(func=cmd_collect)

    p_score = sub.add_parser("score", help="离线比对结果集并出报告")
    p_score.add_argument("--db", default=None)
    p_score.add_argument("--assets", default=None)
    p_score.add_argument("--self-check", action="store_true", help="用 gold_sql 自测判分链路")
    p_score.set_defaults(func=cmd_score)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
