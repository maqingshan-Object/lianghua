# M1 数据采集层

Python 只做采集（每天跑一次批处理写 SQLite），Java 后端只读库，不碰网络。

## 目录结构

```
collector/
├── collect.py                 # 主脚本（CLI 编排）
├── db.py                      # SQLite 建表 / 增量水位 / 日志
├── test_smoke.py              # 无网络冒烟测试（FakeProvider）
├── requirements.txt
├── providers/
│   ├── base.py                # DataProvider 抽象接口
│   ├── akshare_provider.py    # 主数据源（免费，无需注册）
│   └── baostock_provider.py   # 抽查校验 / 备用
└── collect.log                # 运行日志（自动生成）
```

## 怎么跑

```bash
# 1. 建虚拟环境并安装依赖
python3 -m venv ~/workspace/quant/venv
~/workspace/quant/venv/bin/pip install -r requirements.txt

# 2. 无网络冒烟测试（验证全链路逻辑，不需要联网）
cd ~/workspace/quant/collector
~/workspace/quant/venv/bin/python test_smoke.py

# 3. 真实烟雾测试（5 只股票，需可直连国内网络的机器，见下文"网络要求"）
~/workspace/quant/venv/bin/python collect.py --provider akshare \
    --symbols 600519.SH,000001.SZ,000858.SZ,600036.SH,600900.SH \
    --start-date 2024-01-01 --with-stocks --db ../data/quant.db

# 4. 每日收盘后增量（生产定时任务）
~/workspace/quant/venv/bin/python collect.py --start-date 2023-01-01 --db ../data/quant.db

# 5. 每周基本面全量 + baostock 抽查校验
~/workspace/quant/venv/bin/python collect.py --full --crosscheck --with-stocks --db ../data/quant.db
```

常用参数：`--provider {akshare|baostock}` `--symbols` `--limit N` `--start-date/--end-date`
`--with-stocks`（刷新基础信息） `--full`（基本面） `--crosscheck`（校验） `--sleep`（限流间隔）

建议 cron（交易日）：

```
30 15 * * 1-5  ~/workspace/quant/venv/bin/python ~/workspace/quant/collector/collect.py --db ~/workspace/quant/data/quant.db >> ~/workspace/quant/collector/cron.log 2>&1
0  20 * * 0    ... collect.py --full --crosscheck --with-stocks ...   # 周日基本面+校验
```

## 表结构（SQLite: `../data/quant.db`）

- **stocks**(symbol PK, name, market, industry, list_date, is_st, suspended, updated_at)
  symbol 统一为 `600519.SH` / `000001.SZ` / `430047.BJ` 格式。
- **daily_bars**(symbol, trade_date PK, open/high/low/close, volume, amount,
  pct_chg, turnover, pe_ttm, pb_mrq, suspended)
  价格为**前复权价**（指标计算用）；pe_ttm/pb_mrq 在 AKShare 下为 NULL（见已知坑）。
- **fundamentals**(symbol, report_date PK, announce_date, roe, revenue_yoy,
  netprofit_yoy, debt_ratio, ocf, updated_at)
  **announce_date 为公告日期**，回测/选股做 PIT（Point-in-Time）对齐时必须用它，
  不能用 report_date，否则引入前视偏差。
- **collect_log**(task, provider, started_at/finished_at, status, symbols_total/ok/fail, message)
  每次任务留痕；失败会打印 `【采集告警】`（M6 接企业微信/邮件）。

## AKShare 已知坑（akshare 1.19.1，2026-10-18 核实）

1. **`stock_a_indicator_lg` 已被移除**：原来拿个股 PE/PB 的乐咕接口没了，
   所以 `daily_bars.pe_ttm/pb_mrq` 在 AKShare 下置 NULL。估值以后从 Tushare
   或基本面周更新补，不要硬凑。
2. **列名偶发变更**：`akshare_provider.py` 里所有字段映射都用候选名防御式匹配，
   缺列记 warning 不抛异常；拉到空数据也只记 fail 不中断整批。
3. **抓取源网站**：AKShare 本质是爬虫封装，源站改版会偶发失效、无 SLA。
   baostock 做抽查校验（收盘价平均偏离超 0.5% 告警）就是为此设计的。
4. **财务指标列名**：`fundamentals()` 的列映射候选名（如"净资产收益率(加权)(%)"、
   "公告日期"）是按历史版本写的，**必须在可联网机器上首跑时核对日志**，
   若 warning 刷屏说明列名变了，按 README 底部方法更新候选名。
5. **速度**：全市场 5000 只 × 单只约 1s（含 sleep 防限流），全量日线约 1.5 小时，
   适合收盘后批处理；基本面逐只更慢，只在周末 `--full` 跑。

## 网络要求（重要）

AKShare/baostock 的数据源都在国内（东方财富、新浪、baostock）。
**本开发沙箱的出口代理拦截了这些域名，真实采集必须在能直连国内网络的机器上跑**
（Ma 的 Mac、或 Oracle Cloud 等部署机）。沙箱里 `test_smoke.py` 已验证全部逻辑，
部署机上用上面的"真实烟雾测试"命令验证网络链路。

## 增量逻辑

- 日线：按 `MAX(trade_date)` 续拉，不回填重复；周末/节假日返回空即 0 行，不报错。
- 首次运行用 `--start-date`（默认 3 年前）；生产环境首跑建议 `--start-date 2020-01-01`。
