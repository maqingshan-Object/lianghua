"""baostock 校验/备用数据源 Provider。

用途：
1. 主：对 AKShare 拉到的日线做抽查校验（收盘价、复权口径）。
2. 备：AKShare 故障时可临时顶上（字段口径略有差异，见下）。

口径说明：
- adjustflag=2 为前复权，与 AKShare qfq 对齐。
- tradestatus: 1 正常交易 / 0 停牌；isST: 1 是 ST 股。
- 财务季度接口不带公告日期，announce_date 置 NULL（PIT 对齐以 AKShare 为准）。
"""

import logging
import time

import baostock as bs
import pandas as pd

from .base import DataProvider

log = logging.getLogger("collector")


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


class BaostockProvider(DataProvider):
    name = "baostock"

    def __init__(self, sleep: float = 0.3):
        self.sleep = sleep
        self._logged_in = False

    # ---------------- 连接管理 ----------------
    def login(self) -> bool:
        try:
            rs = bs.login()
            self._logged_in = rs.error_code == "0"
            if not self._logged_in:
                log.error("baostock 登录失败: %s %s", rs.error_code, rs.error_msg)
            return self._logged_in
        except Exception as e:
            log.error("baostock 登录异常: %s", e)
            return False

    def logout(self):
        try:
            bs.logout()
        except Exception:
            pass
        self._logged_in = False

    def _ensure(self) -> bool:
        if not self._logged_in:
            return self.login()
        return True

    @staticmethod
    def _bs_code(symbol: str) -> str:
        code6 = symbol.split(".")[0]
        m = symbol.split(".")[-1].upper()
        prefix = {"SH": "sh", "SZ": "sz", "BJ": "bj"}.get(m, "sh")
        return f"{prefix}.{code6}"

    # ---------------- 股票清单 ----------------
    def stock_list(self) -> pd.DataFrame:
        if not self._ensure():
            return pd.DataFrame(columns=["symbol", "name"])
        out = []
        try:
            rs = bs.query_stock_basic()
            while rs.error_code == "0" and rs.next():
                row = rs.get_row_data()  # [code, code_name, ipoDate, outDate, type, status]
                if len(row) < 2:
                    continue
                code, name = row[0], row[1]
                status = row[5] if len(row) > 5 else "1"
                if status != "1":  # 只保留上市状态
                    continue
                out.append({"symbol": self.to_internal(code), "name": name})
        except Exception as e:
            log.error("baostock stock_list 异常: %s", e)
        time.sleep(self.sleep)
        return pd.DataFrame(out)

    def stock_info(self, symbol: str) -> dict:
        info = {"industry": None, "list_date": None, "is_st": 0, "suspended": 0}
        # baostock 基础信息接口不含行业；行业以 AKShare 为准，这里只给上市日期
        if not self._ensure():
            return info
        try:
            rs = bs.query_stock_basic(code=self._bs_code(symbol))
            while rs.error_code == "0" and rs.next():
                row = rs.get_row_data()
                if len(row) > 2 and row[2]:
                    info["list_date"] = str(row[2])[:10]
                break
        except Exception as e:
            log.error("baostock stock_info 异常 %s: %s", symbol, e)
        time.sleep(self.sleep)
        return info

    # ---------------- 日线（前复权） ----------------
    def daily_bars(self, symbol: str, start_date: str, end_date: str) -> pd.DataFrame:
        if not self._ensure():
            return pd.DataFrame()
        fields = ("date,code,open,high,low,close,preclose,volume,amount,"
                  "turn,tradestatus,pctChg,peTTM,pbMRQ,isST")
        rows = []
        try:
            rs = bs.query_history_k_data_plus(
                self._bs_code(symbol), fields,
                start_date=start_date, end_date=end_date,
                frequency="d", adjustflag="2",  # 2 = 前复权
            )
            while rs.error_code == "0" and rs.next():
                d = rs.get_row_data()
                if len(d) < 15:
                    continue
                rows.append({
                    "trade_date": str(d[0])[:10],
                    "open": _f(d[2]), "high": _f(d[3]), "low": _f(d[4]),
                    "close": _f(d[5]), "volume": _f(d[7]), "amount": _f(d[8]),
                    "pct_chg": _f(d[11]), "turnover": _f(d[9]),
                    "pe_ttm": _f(d[12]), "pb_mrq": _f(d[13]),
                    "suspended": 0 if str(d[10]) == "1" else 1,
                })
                if str(d[14]) == "1":
                    rows[-1]["_is_st"] = 1
        except Exception as e:
            log.error("baostock daily_bars 异常 %s: %s", symbol, e)
            return pd.DataFrame()
        time.sleep(self.sleep)
        df = pd.DataFrame(rows)
        if "_is_st" in df.columns:
            df = df.drop(columns=["_is_st"])
        return df

    # ---------------- 基本面（季度，尽力而为） ----------------
    def fundamentals(self, symbol: str) -> pd.DataFrame:
        # baostock 季度接口无公告日期；PIT 对齐以 AKShare 为准，这里只做备用
        if not self._ensure():
            return pd.DataFrame()
        code = self._bs_code(symbol)
        growth, dupont = {}, {}
        try:
            rs = bs.query_growth_data(code=code)
            while rs.error_code == "0" and rs.next():
                r = rs.get_row_data()  # [code,pubYear,pubQuarterDate,statDate,YOYNI,...]
                if len(r) >= 7:
                    growth[str(r[3])[:10]] = {"revenue_yoy": _f(r[5]), "netprofit_yoy": _f(r[6])}
            rs = bs.query_dupont_data(code=code)
            while rs.error_code == "0" and rs.next():
                r = rs.get_row_data()  # [code,pubYear,pubQuarterDate,statDate,dupontROE,...]
                if len(r) >= 5:
                    dupont[str(r[3])[:10]] = {"roe": _f(r[4])}
        except Exception as e:
            log.error("baostock fundamentals 异常 %s: %s", symbol, e)
            return pd.DataFrame()
        time.sleep(self.sleep)
        rows = []
        for rd in sorted(set(growth) | set(dupont)):
            g, d = growth.get(rd, {}), dupont.get(rd, {})
            rows.append({
                "report_date": rd, "announce_date": None,
                "roe": d.get("roe"), "revenue_yoy": g.get("revenue_yoy"),
                "netprofit_yoy": g.get("netprofit_yoy"),
                "debt_ratio": None, "ocf": None,
            })
        return pd.DataFrame(rows)
