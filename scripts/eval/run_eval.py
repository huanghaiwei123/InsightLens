"""
最小评测 runner：把「手动点 6 次接口、肉眼看答案」变成自动跑批 + 按结果集判分。

为什么分两段：
  collect  在线：应用运行时逐题调 /personal/chat/overAll，记录答案、耗时，
                 并从应用日志里提取本轮实际执行的 SQL。
  score    离线：应用停止后打开 data/app.duckdb，分别执行 gold_sql 和
                 generated_sql，比对结果集，出正确率报告。

必须离线的原因：DuckDB 是单写者，应用持有写连接时 Python 侧不能同时开库。
generated_sql 从日志来的原因：/chat/overAll 只返回 GraphReturner{answer}，
不吐 SQL 和结果集；SqlExecuteNode 会把最终执行的 SQL 打进
「[sql-execute] >>> sql=」，且提示词要求单行 SQL，可以稳定提取。

用法（仓库根目录）：
  1) 启动应用并把日志落盘：
       mvn spring-boot:run > app.log 2>&1
  2) 在线采集：
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


def post_question(base_url: str, question: str, timeout: float) -> tuple[int, dict]:
    url = base_url.rstrip("/") + "/personal/chat/overAll"
    body = urllib.parse.urlencode({"userMsg": question}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"},
        method="POST",
    )
    with http_opener().open(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def log_tail(path: Path, offset: int) -> tuple[str, int]:
    if not path.is_file():
        return "", offset
    with open(path, "rb") as f:
        f.seek(offset)
        chunk = f.read()
        return chunk.decode("utf-8", errors="replace"), f.tell()


def extract_sql(log_text: str) -> str | None:
    matches = SQL_LOG.findall(log_text)
    return matches[-1].strip() if matches else None


def cmd_collect(args) -> None:
    assets = resolve_assets(args.assets)
    cases = load_cases(assets)
    log_path = Path(args.log_file).resolve() if args.log_file else None
    collection = out_dir() / "collection.jsonl"

    print(f"[collect] 目标 {args.base_url}，{len(cases)} 题")
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
            status, payload = post_question(args.base_url, case["question"], args.timeout)
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
        latency = round((time.time() - started) * 1000, 1)

        # 等日志缓冲区刷出，再取本轮新增内容
        if log_path:
            time.sleep(args.settle)
        new_text, offset = log_tail(log_path, offset) if log_path else ("", 0)
        generated_sql = extract_sql(new_text)
        # 修正循环会打多条 sql 日志，最后一条才是最终执行的那一条

        data = payload.get("data") or {}
        record = {
            "case_id": case["id"],
            "difficulty": case["difficulty"],
            "question": case["question"],
            "answer": data.get("answer"),
            "questionToUser": data.get("questionToUser"),
            "generated_sql": generated_sql,
            "latency_ms": latency,
            "http_status": status,
            "error": err,
        }
        results.append(record)
        flag = "OK" if status == 200 and not err else "FAIL"
        print(
            f"  {case['id']} [{flag}] {latency:>8.1f}ms  "
            f"sql={'有' if generated_sql else '无'}  answer={(data.get('answer') or '')[:28]}"
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
            # 主判分按「结果集内容」：并列的 ORDER BY 在不同 SQL 写法下顺序可能不同，
            # 语义等价的答案不该因此被判错。行顺序单独记 order_match 供诊断。
            row["execution_match"] = sorted(actual) == sorted(expected)
            row["order_match"] = actual == expected
            if not row["execution_match"]:
                row["error_type"] = "mismatch"
                row["detail"] = (
                    f"expected {len(expected)} rows / actual {len(actual)} rows; "
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
        "notes": "self-check" if args.self_check else "execution_match，结果集按内容比对（忽略行顺序），order_match 单独记录",
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
    p_collect.add_argument("--timeout", type=float, default=120.0)
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
