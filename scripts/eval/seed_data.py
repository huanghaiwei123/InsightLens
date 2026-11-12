"""
开发期造数 + 评测基线自检（示例域：电商）。

用法（在仓库根目录执行）：
    python scripts/eval/seed_data.py                 # 造数并自检 6 条 gold SQL
    python scripts/eval/seed_data.py --force        # 先删业务表再重建
    python scripts/eval/seed_data.py --assets D:/path/to/examples/ecommerce

产物：data/app.duckdb（已在 .gitignore 中排除，不入库）

设计约束（对应 PRD 7.1）：
  1. 固定随机种子 SEED，评测可复现；
  2. 时间铺满 12 个月，支持「上周」「近 30 天」「今年」三类题；
  3. 品类退款率刻意拉开差距，让排序题有区分度；
  4. status 与 pay_time 自洽：CREATED / CLOSED 的 pay_time 必须为 NULL。

评测基准日 EVAL_DATE 是固定值，不随真实日期变化。评测集 gold SQL 里的
「上周」「近 30 天」都按这个基准日写成绝对日期，否则每过一周评测结果就漂移。
本脚本与 eval_set.jsonl 必须共用同一个 EVAL_DATE。
"""

import argparse
import csv
import json
import os
import random
import tempfile
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import duckdb

SEED = 20261111
EVAL_DATE = date(2026, 11, 11)

# 规模按 PRD 7.1 建议值
N_USERS = 5_000
N_PRODUCTS = 500
N_ORDERS = 50_000

CITIES = ["广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "长沙"]
CHANNELS = ["自然流量", "投放", "裂变"]
CATEGORIES = ["女装", "男装", "数码", "家居", "食品"]
REFUND_REASONS = ["尺码不符", "质量问题", "发货慢", "拍错", "其他"]

# 品类退款率差异是造数的核心：q04 排序题要靠它才有区分度。
# 整体乘 REFUND_SCALE 把退款单数控制在 3k 量级，同时保留约 9 倍差距。
CATEGORY_REFUND_RATE = {
    "女装": 0.18,
    "男装": 0.10,
    "数码": 0.04,
    "家居": 0.06,
    "食品": 0.02,
}
REFUND_SCALE = 0.75

# 地区消费差异：只影响成交折扣，保证 order_items.price 始终不高于标价。
# 不这么做的话 q06「客单价最高的 5 个城市」各城市数值几乎相同，排序题失去区分度。
CITY_DISCOUNT = {
    "广州": 1.00,
    "深圳": 0.95,
    "杭州": 0.88,
    "成都": 0.80,
    "武汉": 0.72,
    "西安": 0.64,
    "南京": 0.55,
    "长沙": 0.45,
}

# 每个品类前 4 个商品设为爆款，让 q03「销量前十」头部名次有真实差距
HOT_PER_CATEGORY = 4
HOT_HIT_RATE = 0.25

NULL_MARK = r"\N"
CSV_TABLES = ["users", "products", "orders", "order_items", "refunds"]
TABLE_COLUMNS = {
    "users": ["id", "city", "channel", "register_time"],
    "products": ["id", "name", "category", "price", "stock", "status"],
    "orders": ["id", "user_id", "status", "pay_amount", "pay_time", "created_at"],
    "order_items": ["id", "order_id", "product_id", "quantity", "price"],
    "refunds": ["id", "order_id", "reason", "amount", "created_at"],
}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def resolve_assets(explicit: str | None) -> Path:
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
    raise SystemExit(
        "找不到示例域资产目录，请用 --assets 指定含 schema.sql / eval_set.jsonl 的目录"
    )


def rand_datetime(rng: random.Random, start: datetime, end: datetime) -> datetime:
    span = int((end - start).total_seconds())
    return start + timedelta(seconds=rng.randint(0, span))


def fmt_dt(value: datetime | None) -> str:
    return NULL_MARK if value is None else value.strftime("%Y-%m-%d %H:%M:%S")


def money(value: float) -> str:
    return f"{value:.2f}"


def build_rows(rng: random.Random):
    window_start = datetime.combine(EVAL_DATE - timedelta(days=365), datetime.min.time())
    window_end = datetime.combine(EVAL_DATE, datetime.min.time())

    users = [
        (
            i,
            rng.choice(CITIES),
            rng.choice(CHANNELS),
            fmt_dt(rand_datetime(rng, window_start, window_end)),
        )
        for i in range(1, N_USERS + 1)
    ]

    products = []
    for i in range(1, N_PRODUCTS + 1):
        category = CATEGORIES[(i - 1) * len(CATEGORIES) // N_PRODUCTS]
        price = round(rng.uniform(29, 899), 2)
        # 约 8% 在售商品库存为 0，保证 q02 结果非空
        stock = 0 if rng.random() < 0.08 else rng.randint(1, 300)
        status = 0 if rng.random() < 0.10 else 1
        products.append((i, f"商品{i:04d}", category, money(price), stock, status))

    user_city = {u[0]: u[1] for u in users}
    product_category = {p[0]: p[2] for p in products}
    product_price = {p[0]: float(p[3]) for p in products}

    # 一个订单的明细全部来自它的主品类。否则 q04 按 p.category 分组时，
    # 订单会被摊到多个品类里，造数时刻意设置的退款率差异会被稀释掉。
    category_products: dict[str, list[int]] = {}
    for pid, cat in product_category.items():
        category_products.setdefault(cat, []).append(pid)
    hot_by_category = {
        cat: ids[:HOT_PER_CATEGORY] for cat, ids in category_products.items()
    }

    orders = []
    order_items = []
    refunds = []
    item_id = 0
    refund_id = 0

    for order_id in range(1, N_ORDERS + 1):
        user_id = rng.randint(1, N_USERS)
        created_at = rand_datetime(rng, window_start, window_end)

        # 先定主品类，再按该品类的退款率决定状态，退款率差异才能落到数据里
        primary_category = rng.choice(CATEGORIES)
        city = user_city[user_id]
        discount = CITY_DISCOUNT[city]
        pool = category_products[primary_category]
        hot = hot_by_category[primary_category]
        if rng.random() < CATEGORY_REFUND_RATE[primary_category] * REFUND_SCALE:
            status = "REFUNDED"
        else:
            roll = rng.random()
            status = "CREATED" if roll < 0.08 else ("CLOSED" if roll < 0.18 else "PAID")

        pay_time = None
        if status in ("PAID", "REFUNDED"):
            pay_time = created_at + timedelta(hours=rng.randint(0, 48))
            if pay_time > window_end:
                pay_time = window_end

        # 每单 1~4 条明细；pay_amount 由明细算出来，保证金额自洽
        item_count = rng.randint(1, 4)
        pay_amount = 0.0
        for _ in range(item_count):
            item_id += 1
            pid = rng.choice(hot) if rng.random() < HOT_HIT_RATE else rng.choice(pool)
            quantity = rng.randint(1, 3)
            price = round(product_price[pid] * rng.uniform(0.8, 1.0) * discount, 2)
            pay_amount += price * quantity
            order_items.append(
                (item_id, order_id, pid, quantity, money(price))
            )

        orders.append(
            (
                order_id,
                user_id,
                status,
                money(pay_amount),
                fmt_dt(pay_time),
                fmt_dt(created_at),
            )
        )

        if status == "REFUNDED":
            refund_id += 1
            refunds.append(
                (
                    refund_id,
                    order_id,
                    rng.choice(REFUND_REASONS),
                    money(round(pay_amount * rng.uniform(0.3, 1.0), 2)),
                    fmt_dt(pay_time + timedelta(days=rng.randint(1, 10))),
                )
            )

    return {
        "users": users,
        "products": products,
        "orders": orders,
        "order_items": order_items,
        "refunds": refunds,
    }


def write_csv(table: str, rows, target_dir: Path) -> Path:
    path = target_dir / f"{table}.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(TABLE_COLUMNS[table])
        writer.writerows(rows)
    return path


def copy_into(con, table: str, csv_path: Path) -> None:
    # 路径统一成正斜杠，避免 Windows 反斜杠在 DuckDB 字符串里被当转义符
    posix = csv_path.as_posix()
    con.execute(
        f"COPY {table} FROM '{posix}' (FORMAT CSV, HEADER TRUE, NULLSTR '{NULL_MARK}')"
    )


# 业务表的语义标签：ClarifyNode 只按「表名 | 语义标签 | 结构指纹」选表，
# 标签里必须带关键字段与口径，否则模型无法把「GMV」「退款率」映射到正确的表。
BUSINESS_TAGS = {
    "users": "用户档案：城市、注册渠道、注册时间（按城市分析客单价时关联 orders.user_id）",
    "products": "商品目录：商品名、品类、标价、库存、在售状态（status=1 在售，stock 库存）",
    "orders": "订单主表：实付金额 pay_amount、支付时间 pay_time、状态 status、下单用户 user_id（GMV / 客单价 / 退款率的主表）",
    "order_items": "订单明细：order_id、product_id、购买件数 quantity、成交单价 price（销量 = SUM(quantity)）",
    "refunds": "退款单：order_id、退款原因 reason、退款金额 amount、退款时间（退款率按订单数算）",
}


def create_schema(con, assets: Path, force: bool) -> None:
    # 应用本身没有自动建表机制。ClarifyNode / SchemaRetrieveNode 会查
    # table_registry、upload_batch 等元数据表，评测库必须一并初始化，
    # 否则启动后第一次提问就会报表不存在。
    meta_ddl = repo_root() / "src" / "main" / "resources" / "InsightLens.sql"
    if meta_ddl.is_file():
        con.execute(meta_ddl.read_text(encoding="utf-8"))
    else:
        print(f"[warn] 未找到 {meta_ddl}，跳过元数据表初始化")

    schema_path = assets / "schema.sql"
    if not schema_path.is_file():
        raise SystemExit(f"缺少 DDL: {schema_path}")
    if force:
        for table in reversed(CSV_TABLES):
            con.execute(f"DROP TABLE IF EXISTS {table}")
    con.execute(schema_path.read_text(encoding="utf-8"))


def schema_key(con, table: str) -> str:
    """结构指纹：用户列名小写 + 类型族，按列名排序后以 | 拼接（与 InsightLens.sql 注释口径一致）。"""
    rows = con.execute(
        "SELECT column_name, data_type FROM duckdb_columns() "
        "WHERE schema_name = 'main' AND table_name = ? ORDER BY column_name",
        [table],
    ).fetchall()

    def family(data_type: str) -> str:
        t = data_type.upper()
        if "INT" in t:
            return "integer"
        if any(k in t for k in ("DECIMAL", "DOUBLE", "FLOAT", "REAL", "NUMERIC")):
            return "decimal"
        if any(k in t for k in ("TIMESTAMP", "DATE", "TIME")):
            return "timestamp"
        return "varchar"

    return "|".join(f"{name.lower()}:{family(dtype)}" for name, dtype in rows)


def register_tables(con) -> None:
    """把业务表登记进 table_registry。

    ClarifyNode.tableList() 与 SchemaRetrieveNode 的兜底都查这张表；只建表不登记时，
    候选表清单会退化成「库中暂无任何数据表」，模型拿不到业务表 DDL，
    只能生成 CAST(NULL ...) WHERE false 这类占位 SQL（首次真实基线 0/6 的根因）。
    口径词典存在 orders 表的 COMMENT ON TABLE 里，而 clarify 在 schema_retrieve 之前执行、
    看不到 DDL 注释，所以把 catalog 注释压平后拼进语义标签：口径只有一个来源（DDL），
    造数脚本不必再抄一份。
    upload_batch 不登记：示例表没有 _batch_id 列，登记反而会诱导模型写出不存在的列。
    """
    con.execute("DELETE FROM table_registry")
    created_at = datetime.combine(EVAL_DATE, datetime.min.time())
    for table, tag in BUSINESS_TAGS.items():
        row = con.execute(
            "SELECT comment FROM duckdb_tables() "
            "WHERE schema_name = 'main' AND table_name = ?",
            [table],
        ).fetchone()
        comment = row[0] if row and row[0] else ""
        # 候选表清单按换行分「一行一张表」，注释里的换行必须压平，
        # 否则模型会把口径的每一行误当成一张表。
        full_tag = tag if not comment else tag + "；库内口径：" + comment.replace("\n", "；")
        con.execute(
            "INSERT INTO table_registry(table_name, schema_key, data_tag, created_at) "
            "VALUES (?, ?, ?, ?)",
            [table, schema_key(con, table), full_tag, created_at],
        )
    print(f"[seed] 元数据登记: table_registry {len(BUSINESS_TAGS)} 行")


def validate_gold(con, assets: Path) -> None:
    eval_path = assets / "eval_set.jsonl"
    if not eval_path.is_file():
        print(f"[warn] 未找到评测集 {eval_path}，跳过 gold SQL 自检")
        return
    print("\n=== gold SQL 自检（PRD 7.1：先确认非空且合理）===")
    all_ok = True
    for line in eval_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        case = json.loads(line)
        try:
            rows = con.execute(case["gold_sql"]).fetchall()
            preview = rows[:2]
            print(f"  {case['id']} {case['question']}")
            print(f"      rowCount={len(rows)}  preview={preview}")
            if not rows:
                print("      [FAIL] 结果为空，造数或 gold SQL 有问题")
                all_ok = False
        except Exception as e:
            print(f"  {case['id']} [FAIL] gold SQL 执行报错: {e}")
            all_ok = False
    print("=== 自检结果: " + ("全部通过" if all_ok else "存在失败项") + " ===\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="开发期造数 + gold SQL 自检")
    parser.add_argument("--db", default=None, help="DuckDB 文件路径，默认 data/app.duckdb")
    parser.add_argument("--assets", default=None, help="示例域资产目录")
    parser.add_argument("--force", action="store_true", help="重建业务表（会清空已有示例数据）")
    parser.add_argument("--skip-validate", action="store_true", help="只造数不自检")
    args = parser.parse_args()

    assets = resolve_assets(args.assets)
    db_path = Path(args.db) if args.db else repo_root() / "data" / "app.duckdb"
    db_path.parent.mkdir(parents=True, exist_ok=True)

    rng = random.Random(SEED)
    rows_by_table = build_rows(rng)

    con = duckdb.connect(str(db_path))
    try:
        create_schema(con, assets, args.force)
        with tempfile.TemporaryDirectory(prefix="il_seed_") as tmp:
            tmp_dir = Path(tmp)
            for table in CSV_TABLES:
                csv_path = write_csv(table, rows_by_table[table], tmp_dir)
                copy_into(con, table, csv_path)
                print(f"[seed] {table}: {len(rows_by_table[table]):,} 行")
        counts = {
            t: con.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
            for t in CSV_TABLES
        }
        print(f"[seed] 落库校验: {counts}")
        register_tables(con)
        print(f"[seed] 基准日 EVAL_DATE={EVAL_DATE}, seed={SEED}, db={db_path}")
        if not args.skip_validate:
            validate_gold(con, assets)
    finally:
        con.close()


if __name__ == "__main__":
    main()
