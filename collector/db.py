"""SQLite 存储层：建表、增量查询、采集日志。"""

import os
import sqlite3
from datetime import datetime

SCHEMA = """
CREATE TABLE IF NOT EXISTS stocks (
    symbol      TEXT PRIMARY KEY,   -- 内部代码，如 600519.SH
    name        TEXT,
    market      TEXT,               -- SH / SZ / BJ
    industry    TEXT,
    list_date   TEXT,               -- YYYY-MM-DD
    is_st       INTEGER DEFAULT 0,
    suspended   INTEGER DEFAULT 0,
    updated_at  TEXT
);

CREATE TABLE IF NOT EXISTS daily_bars (
    symbol      TEXT,
    trade_date  TEXT,               -- YYYY-MM-DD
    open        REAL,
    high        REAL,
    low         REAL,
    close       REAL,               -- 前复权价（指标计算用）
    volume      REAL,               -- 手
    amount      REAL,               -- 元
    pct_chg     REAL,               -- 涨跌幅 %
    turnover    REAL,               -- 换手率 %
    pe_ttm      REAL,               -- NULL 表示该数据源未提供
    pb_mrq      REAL,
    suspended   INTEGER DEFAULT 0,
    PRIMARY KEY (symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS fundamentals (
    symbol        TEXT,
    report_date   TEXT,             -- 报告期 YYYY-MM-DD
    announce_date TEXT,             -- 公告日期（PIT 对齐用，可空）
    roe           REAL,             -- 净资产收益率 %
    revenue_yoy   REAL,             -- 营收同比 %
    netprofit_yoy REAL,             -- 净利润同比 %
    debt_ratio    REAL,             -- 资产负债率 %
    ocf           REAL,             -- 经营现金流净额（元）
    updated_at    TEXT,
    PRIMARY KEY (symbol, report_date)
);

CREATE TABLE IF NOT EXISTS collect_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task          TEXT,             -- stocks / daily_bars / fundamentals / crosscheck
    provider      TEXT,
    started_at    TEXT,
    finished_at   TEXT,
    status        TEXT,             -- ok / partial / fail
    symbols_total INTEGER DEFAULT 0,
    symbols_ok    INTEGER DEFAULT 0,
    symbols_fail  INTEGER DEFAULT 0,
    message       TEXT
);
"""


def utcnow() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def get_conn(db_path: str) -> sqlite3.Connection:
    parent = os.path.dirname(os.path.abspath(db_path))
    os.makedirs(parent, exist_ok=True)  # git 不追踪空目录，data/ 可能不存在
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=WAL;")
    return conn


def init_db(db_path: str) -> sqlite3.Connection:
    conn = get_conn(db_path)
    conn.executescript(SCHEMA)
    conn.commit()
    return conn


def get_max_trade_date(conn: sqlite3.Connection, symbol: str):
    row = conn.execute(
        "SELECT MAX(trade_date) FROM daily_bars WHERE symbol = ?", (symbol,)
    ).fetchone()
    return row[0] if row else None


def upsert_bars(conn: sqlite3.Connection, symbol: str, rows: list[dict]) -> int:
    """rows: 已归一化的 dict 列表。返回写入行数。"""
    if not rows:
        return 0
    conn.executemany(
        """INSERT OR REPLACE INTO daily_bars
           (symbol, trade_date, open, high, low, close, volume, amount,
            pct_chg, turnover, pe_ttm, pb_mrq, suspended)
           VALUES (:symbol, :trade_date, :open, :high, :low, :close, :volume, :amount,
                   :pct_chg, :turnover, :pe_ttm, :pb_mrq, :suspended)""",
        [dict(r, symbol=symbol) for r in rows],
    )
    conn.commit()
    return len(rows)


def upsert_stock(conn: sqlite3.Connection, info: dict) -> None:
    conn.execute(
        """INSERT INTO stocks (symbol, name, market, industry, list_date, is_st, suspended, updated_at)
           VALUES (:symbol, :name, :market, :industry, :list_date, :is_st, :suspended, :updated_at)
           ON CONFLICT(symbol) DO UPDATE SET
             name=excluded.name, market=excluded.market, industry=excluded.industry,
             list_date=excluded.list_date, is_st=excluded.is_st,
             suspended=excluded.suspended, updated_at=excluded.updated_at""",
        dict(info, updated_at=utcnow()),
    )
    conn.commit()


def upsert_fundamentals(conn: sqlite3.Connection, symbol: str, rows: list[dict]) -> int:
    if not rows:
        return 0
    conn.executemany(
        """INSERT OR REPLACE INTO fundamentals
           (symbol, report_date, announce_date, roe, revenue_yoy, netprofit_yoy,
            debt_ratio, ocf, updated_at)
           VALUES (:symbol, :report_date, :announce_date, :roe, :revenue_yoy, :netprofit_yoy,
                   :debt_ratio, :ocf, :updated_at)""",
        [dict(r, symbol=symbol, updated_at=utcnow()) for r in rows],
    )
    conn.commit()
    return len(rows)


def log_run(conn: sqlite3.Connection, task: str, provider: str, status: str,
            total: int = 0, ok: int = 0, fail: int = 0, message: str = "",
            started_at: str = "") -> None:
    conn.execute(
        """INSERT INTO collect_log
           (task, provider, started_at, finished_at, status,
            symbols_total, symbols_ok, symbols_fail, message)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (task, provider, started_at, utcnow(), status, total, ok, fail, message),
    )
    conn.commit()
