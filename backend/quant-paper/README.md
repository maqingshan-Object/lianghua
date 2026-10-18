# quant-paper（M3：模拟交易模块）

纯 Java 21，依赖 quant-engine，不引入 Spring。**纯模拟，不含任何券商/交易 API 依赖**，
编译期就不具备实盘能力。职责：虚拟账户 → 每日调仓 → 撮合 → 风控 → 交易记录。

## 模块结构

```
quant-paper/src/main/java/com/quant/paper/
├── model/   PaperAccount / PaperPosition / PaperTrade / PendingOrder
├── data/    PaperDb（paper_* 五表，建表+CRUD，与 QuantDb 共享同一 SQLite 连接）
└── engine/
    ├── PaperTrader   每日调仓编排（runDay）
    ├── Matcher       模拟撮合（T+1 开盘成交、涨跌停/停牌、滑点、佣金印花税）
    ├── RiskManager   独立风控（单日熔断、连续亏损暂停，只停新开仓）
    └── TradeReason    理由 JSON 构建器（无理由的交易在入库时直接拒绝）
└── cli/     PaperCommand（命令行入口）
```

## 每日流程 runDay(D)（D 为收盘后）

1. **结算挂单**：`signal_date < D` 的 PENDING 单按 D 开盘价成交。
   涨停买不到 / 跌停卖不出 / 停牌 → 保留重试（超 `paper.order_retry_days` 天作废）。
2. **估值**：按 D 收盘价算总资产，记 `paper_daily_nav`（净值曲线）。
3. **风控**：单日亏损超 `paper.daily_loss_halt_pct`（默认 3%）→ 当天停开新仓；
   连续 `paper.consecutive_loss_halt_days`（默认 5）天亏损 → 暂停新开仓并告警。
   卖出（尤其止损）永远放行。
4. **选股**：复用 M2 `SelectorEngine.select(D)`（结果同样写入 `select_runs`）。
5. **卖出**（优先级：止损 > 止盈 > 信号消失）：持仓 `buyDate < D`（T+1）才允许；
   信号消失 = 当日未入选候选清单（含跌破阈值与跌出 TopN）。生成 PENDING 卖单。
6. **买入**：候选按分排序；跳过已持仓/已挂单；单只预算 = 总资产 × `paper.max_position_pct`
  （默认 20%），整手向下取整，不足一手跳过；总仓位不超过 `paper.max_total_position_pct`
   （默认 80%）。生成 PENDING 买单，T+1 开盘成交。

止损/目标位按**持仓成本 × 当前配置**重算（配置可能已变更，以当前为准）。

## 构建与运行

```bash
cd ~/workspace/quant/backend
bash build-paper.sh test   # 编译 + 组装 fat jar + 跑全部单测（16 个）

# 单日
java -jar quant-paper/target/quant-paper-1.0.0.jar --db ~/workspace/quant/data/quant.db --date 2025-07-01
# 区间（逐日顺序执行，支持历史补跑）
java -jar quant-paper/target/quant-paper-1.0.0.jar --db <库> --from 2025-06-01 --to 2025-07-01
# 调参（生成新版本，默认下一自然日生效；--effective-date 覆盖则指定日生效）
java -jar quant-paper/target/quant-paper-1.0.0.jar --db <库> --set "fusion.threshold=55;trade.max_positions=8"
# 重置模拟账户（危险：清空所有 paper_* 表）
java -jar quant-paper/target/quant-paper-1.0.0.jar --db <库> --reset [--initial-capital 100000]
```

沙箱说明同 M2：Maven 跑不通，用 `build-paper.sh`（javac + libs/ + 手工 fat jar）；
`pom.xml` 保留给正常网络环境（`mvn package`）。

## 表结构（paper_*，PaperDb 自动建表）

- `paper_account`：单行（id=1）：cash / total_asset / updated_date / version_id
- `paper_positions`：symbol PK / name / qty / cost_price / buy_date / buy_reason / version_id
- `paper_trades`：signal_date / trade_date / symbol / side / price / qty / commission /
  stamp_tax / reason_json（非空强制）/ version_id / realized_pnl（卖出时=净额-成本）
- `paper_pending_orders`：symbol / side / qty / ref_price / signal_date / reason_json /
  version_id / status(PENDING/FILLED/CANCELLED) / retries
- `paper_daily_nav`：trade_date PK / total_asset / cash / position_value / daily_pnl / version_id

## 配置项（strategy_config，全部版本化）

| key | 默认 | 说明 |
|---|---|---|
| paper.initial_capital | 100000 | 初始虚拟资金（元） |
| paper.max_position_pct | 20 | 单只仓位上限 % |
| paper.max_total_position_pct | 80 | 总仓位上限 % |
| paper.commission_rate | 0.00025 | 佣金费率 |
| paper.commission_min | 5 | 佣金单笔保底（元） |
| paper.stamp_tax_rate | 0.001 | 印花税（卖出） |
| paper.slippage_pct | 0.1 | 单边滑点 % |
| paper.daily_loss_halt_pct | 3 | 单日亏损熔断 % |
| paper.consecutive_loss_halt_days | 5 | 连续亏损暂停天数 |
| paper.order_retry_days | 3 | 挂单无法成交最大重试天数 |
| trade.stop_loss_pct / trade.take_profit_pct / trade.max_positions | 8 / 20 / 10 | 沿用 M2（止损/止盈/最多持仓） |

## 已知限制（诚实）

1. **"信号消失"含跌出 TopN**：持仓当日未入选候选清单即卖（含分数仍达标但排名掉出 TopN 的情况），
   这是按日轮动策略的本意；如需"达标就持有"，M4/M5 再加持有逻辑。
2. **涨跌停用 pct_chg 启发式判定**（≥9.5% / ST ≥4.5%），pct_chg 为空时不判定（视为可交易）。
3. **全新库跑历史日期**：v1 按运行日生效（PaperTrader.ensureConfig），避免够不着版本；
   CLI 不要在 runDay 前调 `ensureDefaults()`（它按今天生效）。
4. **AI 通道仍为 Mock**（沿用 M2）：`--ai-mock` 启用；真实 DeepSeek 接线等 Ma 提供 Key。
5. **非交易日跳过**：全市场当日无行情则整日跳过（不记净值）。
6. **输出非投资建议**：CLI 尾部保留声明；模拟赚不代表实盘能赚。
