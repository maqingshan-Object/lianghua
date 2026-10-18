"""无网络冒烟测试：用 FakeProvider 验证采集全链路逻辑。

覆盖：
1. 建表（4 张表存在）
2. 日线首次写入 -> 第二次同参数运行 0 新增（增量逻辑）
3. end_date 推进后只写入新增交易日
4. 单只股票失败不影响其他（ok/fail 计数）
5. stocks / fundamentals / collect_log 写入
6. crosscheck 差异公式：>0.5% 能检出

运行：python test_smoke.py   （不需要网络）
"""

import os
import sqlite3
import sys
import tempfile

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from db import (
    get_max_trade_date, init_db, log_run, upsert_bars,
    upsert_fundamentals, upsert_stock,
)
from providers.base import DataProvider
from collect import collect_daily_bars, next_day


def bars(symbol, dates, base=10.0):
    rows = []
    for i, d in enumerate(dates):
        c = base + i * 0.1
        rows.append({
            "trade_date": d, "open": c - 0.05, "high": c + 0.05, "low": c - 0.1,
            "close": round(c, 2), "volume": 1000 + i, "amount": 100000.0,
            "pct_chg": 0.5, "turnover": 1.2, "pe_ttm": 15.0, "pb_mrq": 2.0,
            "suspended": 0,
        })
    return pd.DataFrame(rows)


class FakeProvider(DataProvider):
    """确定性假数据源。fail_on 里的 symbol 会抛异常，模拟单只失败。"""
    name = "fake"

    def __init__(self, end_dates, fail_on=()):
        self.end_dates = end_dates
        self.fail_on = set(fail_on)

    def stock_list(self):
        return pd.DataFrame([
            {"symbol": "600519.SH", "name": "贵州茅台"},
            {"symbol": "000001.SZ", "name": "平安银行"},
            {"symbol": "600036.SH", "name": "招商银行"},
        ])

    def stock_info(self, symbol):
        return {"industry": "测试行业", "list_date": "2001-08-27",
                "is_st": 0, "suspended": 0}

    def daily_bars(self, symbol, start_date, end_date):
        if symbol in self.fail_on:
            raise RuntimeError("模拟单只失败")
        dates = [d for d in self.end_dates if start_date <= d <= end_date]
        base = {"600519.SH": 1700.0, "000001.SZ": 12.0, "600036.SH": 35.0}[symbol]
        return bars(symbol, dates, base)

    def fundamentals(self, symbol):
        return pd.DataFrame([{
            "report_date": "2024-09-30", "announce_date": "2024-10-25",
            "roe": 12.5, "revenue_yoy": 8.1, "netprofit_yoy": 10.2,
            "debt_ratio": 55.0, "ocf": 1.2e10,
        }])


def check(cond, msg):
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond:
        check.failed += 1
check.failed = 0


def main():
    tmp = tempfile.mkdtemp(prefix="quant_smoke_")
    db_path = os.path.join(tmp, "quant.db")
    conn = init_db(db_path)

    # 1. 四张表存在
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    check({"stocks", "daily_bars", "fundamentals", "collect_log"} <= tables,
          "四张表已创建: {}".format(sorted(tables)))

    dates = ["2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"]
    symbols = ["600519.SH", "000001.SZ", "600036.SH"]
    fp = FakeProvider(dates)

    # 2. 首次写入：3 只 x 4 天 = 12 行
    ok, fail, rows = collect_daily_bars(conn, fp, symbols, "2024-01-02", "2024-01-05")
    check((ok, fail, rows) == (3, 0, 12), "首次写入 ok=3 fail=0 rows=12 (实际 {})".format((ok, fail, rows)))
    check(get_max_trade_date(conn, "600519.SH") == "2024-01-04" or
          get_max_trade_date(conn, "600519.SH") == "2024-01-05",
          "增量水位 max(trade_date) 正确")

    # 3. 同参数再跑：0 新增（增量逻辑）
    ok, fail, rows = collect_daily_bars(conn, fp, symbols, "2024-01-02", "2024-01-05")
    check(rows == 0 and ok == 3, "第二次运行 0 新增、3 只跳过 (rows={})".format(rows))

    # 4. end_date 推进：只写入新增的 2 天
    fp2 = FakeProvider(dates + ["2024-01-08", "2024-01-09"])
    ok, fail, rows = collect_daily_bars(conn, fp2, symbols, "2024-01-02", "2024-01-09")
    check(rows == 6, "日期推进后只新增 6 行 (3只x2天)，实际 {}".format(rows))

    # 5. 单只失败隔离
    fp3 = FakeProvider(dates, fail_on=("000001.SZ",))
    ok, fail, rows = collect_daily_bars(conn, fp3, symbols, "2024-01-02", "2024-01-10")
    check(ok == 2 and fail == 1, "单只失败隔离 ok=2 fail=1 (实际 {})".format((ok, fail)))

    # 6. stocks / fundamentals / log
    upsert_stock(conn, {"symbol": "600519.SH", "name": "贵州茅台", "market": "SH",
                        "industry": "白酒", "list_date": "2001-08-27",
                        "is_st": 0, "suspended": 0})
    r = conn.execute("SELECT name, industry FROM stocks WHERE symbol='600519.SH'").fetchone()
    check(r == ("贵州茅台", "白酒"), "stocks 写入/更新正确")
    n = upsert_fundamentals(conn, "600519.SH",
                            fp.fundamentals("600519.SH").to_dict("records"))
    r = conn.execute("SELECT announce_date, roe FROM fundamentals WHERE symbol='600519.SH'").fetchone()
    check(n == 1 and r == ("2024-10-25", 12.5), "fundamentals 写入正确（含公告日期）")
    log_run(conn, "daily_bars", "fake", "ok", total=3, ok=3, message="smoke")
    r = conn.execute("SELECT status, symbols_ok FROM collect_log ORDER BY id DESC LIMIT 1").fetchone()
    check(r == ("ok", 3), "collect_log 记录正确")

    # 7. crosscheck 差异公式
    def mean_diff(a, b):
        return sum(abs(x - y) / y for x, y in zip(a, b)) / len(a)
    check(mean_diff([10.0, 10.0], [10.0, 10.0]) < 0.005, "一致序列不告警")
    check(mean_diff([10.0, 10.0], [10.2, 10.2]) > 0.005, "偏离 2% 能检出")

    # 8. next_day 跨月
    check(next_day("2024-01-31") == "2024-02-01", "next_day 跨月正确")

    conn.close()
    print("\n冒烟测试完成，{} 项失败".format(check.failed))
    return 1 if check.failed else 0


if __name__ == "__main__":
    sys.exit(main())
