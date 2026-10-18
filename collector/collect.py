"""M1 数据采集主脚本。

用法示例：
  # 烟雾测试（5 只股票，日线增量）
  python collect.py --provider baostock \\
      --symbols 600519.SH,000001.SZ,000858.SZ,600036.SH,000002.SZ \\
      --start-date 2024-01-01 --with-stocks

  # 每日收盘后增量（默认 provider=akshare）
  python collect.py --start-date 2023-01-01

  # 每周基本面全量 + 抽查校验
  python collect.py --full --crosscheck --with-stocks

采集失败只记日志+告警，不抛异常中断整批（单只股票失败不影响其他）。
"""

import argparse
import logging
import sys
import time
from datetime import date, timedelta

from db import (
    get_max_trade_date, init_db, log_run, upsert_bars,
    upsert_fundamentals, upsert_stock, utcnow,
)
from providers import AkshareProvider, BaostockProvider

log = logging.getLogger("collector")

PROVIDERS = {"akshare": AkshareProvider, "baostock": BaostockProvider}


def send_alert(message: str):
    """告警出口：目前打印到控制台，M6 部署时接企业微信/邮件/短信。

    TODO(M6): 接入真实通知渠道。
    """
    print("【采集告警】{}".format(message), flush=True)
    log.error("ALERT: %s", message)


def parse_args():
    p = argparse.ArgumentParser(description="A股日线数据采集（M1）")
    p.add_argument("--provider", choices=["akshare", "baostock"], default="akshare")
    p.add_argument("--db", default="../data/quant.db", help="SQLite 路径")
    p.add_argument("--symbols", default="", help="逗号分隔，如 600519.SH,000001.SZ；为空则拉全市场")
    p.add_argument("--limit", type=int, default=0, help="全市场模式下最多拉 N 只（调试用）")
    p.add_argument("--start-date", default="", help="无历史数据时的起始日，默认 3 年前")
    p.add_argument("--end-date", default="", help="截止日，默认今天")
    p.add_argument("--with-stocks", action="store_true", help="同时刷新 stocks 基础信息表")
    p.add_argument("--full", action="store_true", help="同时更新基本面（每周跑一次）")
    p.add_argument("--crosscheck", action="store_true", help="用 baostock 抽查校验日线")
    p.add_argument("--sleep", type=float, default=0.5, help="单只股票间隔秒数，防限流")
    p.add_argument("--log", default="collect.log", help="日志文件")
    return p.parse_args()


def next_day(d: str) -> str:
    dt = date.fromisoformat(d) + timedelta(days=1)
    return dt.isoformat()


def collect_daily_bars(conn, provider, symbols, start_fallback, end_date, log_progress=False):
    """日线增量采集。返回 (ok, fail, rows_total)。"""
    ok = fail = rows_total = 0
    for i, sym in enumerate(symbols, 1):
        try:
            maxd = get_max_trade_date(conn, sym)
            start = next_day(maxd) if maxd else start_fallback
            if start > end_date:
                ok += 1
                continue  # 已是最新，无需拉取
            df = provider.daily_bars(sym, start, end_date)
            n = upsert_bars(conn, sym, df.to_dict("records") if not df.empty else [])
            rows_total += n
            ok += 1
            if log_progress and i % 200 == 0:
                log.info("进度 %d/%d", i, len(symbols))
        except Exception as e:
            fail += 1
            log.warning("日线失败 %s: %s", sym, e)
    return ok, fail, rows_total


def main() -> int:
    args = parse_args()
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.FileHandler(args.log, encoding="utf-8"),
                  logging.StreamHandler(sys.stdout)],
    )

    conn = init_db(args.db)
    provider = PROVIDERS[args.provider](sleep=args.sleep)
    if args.provider == "baostock" and not provider.login():
        send_alert("baostock 登录失败，本次采集终止")
        log_run(conn, "daily_bars", args.provider, "fail", message="baostock 登录失败",
                started_at=utcnow())
        return 1

    end_date = args.end_date or date.today().isoformat()
    default_start = (date.today() - timedelta(days=3 * 365)).isoformat()
    start_fallback = args.start_date or default_start

    # ---- 确定股票池 ----
    if args.symbols:
        symbols = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
        names = {}
    else:
        t0 = utcnow()
        df = provider.stock_list()
        log_run(conn, "stocks_list", args.provider,
                "ok" if not df.empty else "fail",
                total=len(df), ok=len(df), message="", started_at=t0)
        if df.empty:
            send_alert("股票清单拉取为空，本次采集终止")
            return 1
        if args.limit:
            df = df.head(args.limit)
        symbols = df["symbol"].tolist()
        names = dict(zip(df["symbol"], df["name"]))
    log.info("股票池: %d 只, provider=%s", len(symbols), args.provider)

    # ---- stocks 基础信息 ----
    if args.with_stocks:
        t0 = utcnow()
        ok = fail = 0
        for sym in symbols:
            try:
                info = provider.stock_info(sym)
                upsert_stock(conn, {
                    "symbol": sym, "name": names.get(sym, sym),
                    "market": sym.split(".")[-1],
                    "industry": info.get("industry"),
                    "list_date": info.get("list_date"),
                    "is_st": info.get("is_st", 0),
                    "suspended": info.get("suspended", 0),
                })
                ok += 1
            except Exception as e:
                fail += 1
                log.warning("stocks 失败 %s: %s", sym, e)
        log_run(conn, "stocks", args.provider,
                "ok" if fail == 0 else "partial",
                total=len(symbols), ok=ok, fail=fail, started_at=t0)
        if fail:
            send_alert("stocks 基础信息 {}/{} 只失败".format(fail, len(symbols)))

    # ---- 日线增量 ----
    t0 = utcnow()
    ok, fail, rows_total = collect_daily_bars(
        conn, provider, symbols, start_fallback, end_date, log_progress=True)
    status = "ok" if fail == 0 else ("partial" if ok else "fail")
    log_run(conn, "daily_bars", args.provider, status,
            total=len(symbols), ok=ok, fail=fail,
            message="写入 {} 行".format(rows_total), started_at=t0)
    log.info("日线完成: ok=%d fail=%d rows=%d", ok, fail, rows_total)
    if fail:
        send_alert("日线采集 {}/{} 只失败，详见 collect.log".format(fail, len(symbols)))

    # ---- 基本面（每周） ----
    if args.full:
        t0 = utcnow()
        ok = fail = 0
        for sym in symbols:
            try:
                df = provider.fundamentals(sym)
                upsert_fundamentals(conn, sym, df.to_dict("records") if not df.empty else [])
                ok += 1
            except Exception as e:
                fail += 1
                log.warning("基本面失败 %s: %s", sym, e)
        log_run(conn, "fundamentals", args.provider,
                "ok" if fail == 0 else "partial",
                total=len(symbols), ok=ok, fail=fail, started_at=t0)
        if fail:
            send_alert("基本面采集 {}/{} 只失败".format(fail, len(symbols)))

    # ---- baostock 抽查校验 ----
    if args.crosscheck:
        run_crosscheck(conn, provider, symbols, args)

    if args.provider == "baostock":
        provider.logout()
    conn.close()
    log.info("采集结束")
    return 0


def run_crosscheck(conn, provider, symbols, args):
    """抽查：provider 日线收盘价 vs baostock 同口径，差异超 0.5% 告警。"""
    if args.provider == "baostock":
        log.info("crosscheck 跳过：主数据源已是 baostock")
        return
    t0 = utcnow()
    bs = BaostockProvider(sleep=args.sleep)
    if not bs.login():
        send_alert("crosscheck 跳过：baostock 登录失败")
        log_run(conn, "crosscheck", "baostock", "fail",
                message="baostock 登录失败", started_at=t0)
        return

    sample = symbols[:20]
    warn = 0
    for sym in sample:
        try:
            rows = conn.execute(
                "SELECT trade_date, close FROM daily_bars WHERE symbol=? "
                "ORDER BY trade_date DESC LIMIT 20", (sym,)).fetchall()
            if not rows:
                continue
            start, end = rows[-1][0], rows[0][0]
            ref = bs.daily_bars(sym, start, end)
            if ref.empty:
                continue
            ref_map = dict(zip(ref["trade_date"], ref["close"]))
            diffs = [abs(c - ref_map[d]) / ref_map[d]
                     for d, c in rows if d in ref_map and ref_map[d]]
            if diffs:
                mean_diff = sum(diffs) / len(diffs)
                if mean_diff > 0.005:
                    warn += 1
                    log.warning("校验差异超标 %s: 平均偏离 %.2f%%", sym, mean_diff * 100)
        except Exception as e:
            log.warning("校验异常 %s: %s", sym, e)
    bs.logout()
    status = "ok" if warn == 0 else "partial"
    log_run(conn, "crosscheck", "baostock", status,
            total=len(sample), ok=len(sample) - warn, fail=warn,
            message="{} 只差异超 0.5%".format(warn), started_at=t0)
    if warn:
        send_alert("crosscheck: {} 只股票收盘价差异超 0.5%".format(warn))


if __name__ == "__main__":
    sys.exit(main())
