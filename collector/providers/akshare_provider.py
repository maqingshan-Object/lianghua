"""AKShare 主数据源 Provider。

已知限制（2026-10-18 实测 akshare 1.19.1）：
- stock_a_indicator_lg 已被移除，AKShare 日线接口不含 PE/PB 估值字段，
  daily_bars 的 pe_ttm/pb_mrq 置 NULL（估值以后从 Tushare/基本面周更新补）。
- 列名映射用候选名防御式匹配：AKShare 接口偶发改列名，缺列记 warning 不抛异常。
- 所有网络异常内部消化返回空结果，由 collect.py 统一记日志/告警。
"""

import logging
import time

import akshare as ak
import pandas as pd

from .base import DataProvider

log = logging.getLogger("collector")


def _pick(row: dict, candidates: list[str]):
    for c in candidates:
        if c in row and row[c] not in (None, "", "-"):
            return row[c]
    return None


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def _date(x) -> str | None:
    if x is None:
        return None
    s = str(x).strip()[:10].replace("/", "-")
    return s if len(s) == 10 else None


class AkshareProvider(DataProvider):
    name = "akshare"

    def __init__(self, sleep: float = 0.5, retries: int = 2):
        self.sleep = sleep
        self.retries = retries

    def _call(self, fn, *args, **kwargs):
        last = None
        for i in range(self.retries + 1):
            try:
                return fn(*args, **kwargs)
            except Exception as e:  # 网络抖动/接口变更都先吞掉重试
                last = e
                log.warning("akshare 调用失败(%s) 第%d次: %s", fn.__name__, i + 1, e)
                time.sleep(self.sleep * (i + 1))
        log.error("akshare 调用放弃 %s: %s", fn.__name__, last)
        return None

    # ---------------- 股票清单 ----------------
    def stock_list(self) -> pd.DataFrame:
        df = self._call(ak.stock_info_a_code_name)
        if df is None or df.empty:
            return pd.DataFrame(columns=["symbol", "name"])
        df = df.rename(columns={"代码": "code", "名称": "name"})
        out = []
        for _, r in df.iterrows():
            code = str(r.get("code", "")).strip()
            if len(code) == 6 and code.isdigit():
                out.append({"symbol": self.to_internal(code), "name": str(r.get("name", ""))})
        time.sleep(self.sleep)
        return pd.DataFrame(out)

    # ---------------- 个股基础信息 ----------------
    def stock_info(self, symbol: str) -> dict:
        info = {"industry": None, "list_date": None, "is_st": 0, "suspended": 0}
        df = self._call(ak.stock_individual_info_em, symbol=self.code6(symbol))
        time.sleep(self.sleep)
        if df is None or df.empty:
            return info
        kv = dict(zip(df["item"].astype(str), df["value"].astype(str)))
        info["industry"] = kv.get("行业")
        ld = kv.get("上市时间")
        if ld and len(ld) >= 8 and ld[:8].isdigit():
            info["list_date"] = f"{ld[:4]}-{ld[4:6]}-{ld[6:8]}"
        name = kv.get("股票简称", "")
        if "*ST" in name or name.startswith("ST"):
            info["is_st"] = 1
        return info

    # ---------------- 日线（前复权） ----------------
    def daily_bars(self, symbol: str, start_date: str, end_date: str) -> pd.DataFrame:
        code = self.code6(symbol)
        s = start_date.replace("-", "")
        e = end_date.replace("-", "")
        df = self._call(
            ak.stock_zh_a_hist, symbol=code, period="daily",
            start_date=s, end_date=e, adjust="qfq",
        )
        time.sleep(self.sleep)
        if df is None or df.empty:
            return pd.DataFrame()
        cols = {
            "日期": "trade_date", "开盘": "open", "最高": "high", "最低": "low",
            "收盘": "close", "成交量": "volume", "成交额": "amount",
            "涨跌幅": "pct_chg", "换手率": "turnover",
        }
        df = df.rename(columns={k: v for k, v in cols.items() if k in df.columns})
        rows = []
        for _, r in df.iterrows():
            d = _date(r.get("trade_date"))
            if not d:
                continue
            rows.append({
                "trade_date": d,
                "open": _f(r.get("open")), "high": _f(r.get("high")),
                "low": _f(r.get("low")), "close": _f(r.get("close")),
                "volume": _f(r.get("volume")), "amount": _f(r.get("amount")),
                "pct_chg": _f(r.get("pct_chg")), "turnover": _f(r.get("turnover")),
                "pe_ttm": None, "pb_mrq": None,   # AKShare 日线无估值字段，见模块 docstring
                "suspended": 0,
            })
        return pd.DataFrame(rows)

    # ---------------- 基本面（季度财务，带公告日期） ----------------
    def fundamentals(self, symbol: str) -> pd.DataFrame:
        code = self.code6(symbol)
        df = self._call(ak.stock_financial_analysis_indicator, symbol=code)
        time.sleep(self.sleep)
        if df is None or df.empty:
            return pd.DataFrame()
        rows = []
        for _, r in df.iterrows():
            row = r.to_dict()
            report = _date(_pick(row, ["日期"]))
            if not report:
                continue
            rows.append({
                "report_date": report,
                "announce_date": _date(_pick(row, ["公告日期"])),  # PIT 对齐关键；缺失则记 NULL
                "roe": _f(_pick(row, ["净资产收益率(加权)(%)", "净资产收益率(%)"])),
                "revenue_yoy": _f(_pick(row, ["营业总收入同比增长(%)", "主营业务收入增长率(%)"])),
                "netprofit_yoy": _f(_pick(row, ["归属净利润同比增长(%)", "净利润同比增长(%)"])),
                "debt_ratio": _f(_pick(row, ["资产负债率(%)"])),
                "ocf": _f(_pick(row, ["经营现金流量净额(元)", "经营活动产生的现金流量净额(元)"])),
            })
        return pd.DataFrame(rows)
