"""数据源 Provider 抽象接口。

内部代码统一用 "600519.SH" / "000001.SZ" / "430001.BJ" 格式，
各 Provider 负责与自家代码格式互转。
"""

from abc import ABC, abstractmethod

import pandas as pd


class DataProvider(ABC):
    name = "base"

    # ---------- 代码格式互转 ----------
    @staticmethod
    def to_internal(raw: str, market_hint: str = "") -> str:
        """'sh.600519' / '600519' -> '600519.SH'"""
        r = raw.strip().lower().replace("sh", "").replace("sz", "").replace("bj", "")
        r = r.strip(". ").upper()
        if r.startswith(("60", "68", "90")):
            m = "SH"
        elif r.startswith(("00", "30", "20")):
            m = "SZ"
        elif r.startswith("4") or r.startswith("8"):
            m = "BJ"
        else:
            m = market_hint.upper() or "SH"
        return f"{r}.{m}"

    @staticmethod
    def market_of(symbol: str) -> str:
        return symbol.split(".")[-1].upper()

    @staticmethod
    def code6(symbol: str) -> str:
        return symbol.split(".")[0]

    # ---------- 接口 ----------
    @abstractmethod
    def stock_list(self) -> pd.DataFrame:
        """全市场股票清单，列：symbol, name"""

    @abstractmethod
    def stock_info(self, symbol: str) -> dict:
        """单个股票基础信息：industry, list_date, is_st, suspended"""

    @abstractmethod
    def daily_bars(self, symbol: str, start_date: str, end_date: str) -> pd.DataFrame:
        """日线（前复权价）。
        列：trade_date, open, high, low, close, volume, amount,
            pct_chg, turnover, pe_ttm, pb_mrq, suspended
        日期格式 YYYY-MM-DD；取不到返回空 DataFrame（不要抛异常）。"""

    @abstractmethod
    def fundamentals(self, symbol: str) -> pd.DataFrame:
        """季度财务数据。
        列：report_date, announce_date, roe, revenue_yoy, netprofit_yoy,
            debt_ratio, ocf。取不到返回空 DataFrame。"""
