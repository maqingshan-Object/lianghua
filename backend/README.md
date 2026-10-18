# quant-engine（M2：Java 选股引擎）

纯 Java 21，不引入 Spring（Spring 在 M5 才用）。职责：三层过滤 → 因子打分 →
双通道融合 → 输出候选清单。不碰模拟交易/回测/Web。

## 模块结构

```
quant-engine/src/main/java/com/quant/engine/
├── model/        Stock / Bar / Fundamental / FundamentalSnapshot(PIT) / Signal / Candidate / FilterResult
├── data/         QuantDb(SQLite 访问) / MarketDataService(缓存门面)
├── config/       StrategyConfig(配置项+默认+JSON) / ConfigCenter(版本化)
├── filter/       StockFilter(接口) / BasicFilter(L1) / FundamentalFilter(L2,PIT)
├── strategy/     SignalStrategy(统一接口) / Indicators(MA/突破/量比工具)
│                 MaAlignmentStrategy(均线多头) / BreakoutStrategy(N日突破)
│                 VolumeSurgeStrategy(放量) / PullbackStabilizeStrategy(回调企稳)
│                 StrategyRegistry(可插拔注册表)
│                 AISignalStrategy(AI 通道接口) / MockAISignalStrategy(Mock)
├── scoring/      FusionMode(ENGINE_ONLY/AI_ONLY/FUSION) / FusionScorer(融合公式)
├── select/       SelectorEngine(编排) / CandidateBuilder(买入区间/止损/目标)
└── cli/          SelectCommand(命令行入口)
```

## quant-paper（M3：模拟交易，纯模拟无实盘）

`quant-paper/` 模块，依赖 quant-engine。详见 [quant-paper/README.md](quant-paper/README.md)。

- 每日流程：结算挂单（T+1 开盘成交）→ 估值记净值 → 风控 → M2 选股 →
  卖出（止损>止盈>信号消失）→ 买入（预算/仓位上限/整手）。
- 撮合：涨停买不到/跌停卖不出/停牌作废（重试 3 天），佣金万2.5（5元保底）+
  印花税千分之一 + 双边滑点 0.1%，均可配。
- 每笔挂单/成交强制携带理由 JSON + version_id，无理由拒绝入库。
- 构建：`bash build-paper.sh test`（16 个单测全绿）；CLI：`--date/--from/--to/--set/--reset`。

## quant-backtest（M4：回测验证，纯 Java 无 Spring）

`quant-backtest/` 模块，依赖 quant-engine + quant-paper。详见 [quant-backtest/README.md](quant-backtest/README.md)。

- 历史回放：复用 M3 `PaperTrader.runDay`（撮合/费用/风控不重写），在 `[from,to]`
  逐交易日推进；回放前锁定 `--strategy-config-version` 为全区间同一版本。
- 回放在源库的**临时拷贝**上执行，不污染线上 `paper_*` 模拟账户。
- 指标：总收益/年化/最大回撤/夏普/胜率/盈亏比/平均持仓天数/持有5·10·20天收益分布/
  样本内8:2外切片；基准沪深300（000300.SH）对比 + 超额收益。
- 单文件 HTML 报告：ECharts 图（CDN）+ 文字版表格降级；假设声明区逐条列出；
  数字全部来自真实计算，无数据时显示"—"。
- 构建：`bash build-backtest.sh test`（12 个单测全绿）；CLI：`--db/--from/--to/--strategy-config-version/--out`。

## 构建与运行

**常规网络**（Ma 的 Mac / 部署机）：

```bash
cd ~/workspace/quant/backend
mvn package -DskipTests        # 生成 quant-engine/target/quant-engine-1.0.0.jar（shade fat jar）
java -jar quant-engine/target/quant-engine-1.0.0.jar --date 2025-06-30 --db ~/workspace/quant/data/quant.db --ai-mock
```

CLI 参数：`--date YYYY-MM-DD`（必填，选股日） `--db 路径`（必填）
`--top N`（覆盖候选数，仅本次） `--ai-mock`（启用 AI 通道 Mock 打分）

**本沙箱说明**：沙箱出口代理与 Maven wagon 不兼容（见"已知限制"），
此处用 `libs/` 下 curl 下载的 jar + `javac` 直接编译验证，
fat jar 为手工组装（target/quant-engine-1.0.0.jar，可直接 `java -jar` 运行）。
`pom.xml` 为准构建描述，在常规网络下 `mvn package` 即可。

测试（35 个，JUnit5 + AssertJ）：

```bash
java -jar libs/junit-platform-console-standalone-1.11.4.jar \
  --class-path "quant-engine/target/classes:quant-engine/target/test-classes:libs/*" \
  --scan-class-path --include-classname='.*Test'
```

覆盖：L1 边界（ST/停牌/次新/年限）、L2 的 PIT（未来公告财报不可用）、
融合公式（AI100/引擎50/对半/阈值60→75分入选）、配置版本（保存/次日生效/回滚/未知key拒绝）、
策略插拔、端到端选股（含 select_runs/select_candidates 持久化与 version_id 关联）。

## 表依赖

- **只读 M1 的表**：stocks / daily_bars / fundamentals / collect_log（M2 不写 M1 的表）。
- **M2 自建表**（QuantDb 初始化时自动建）：
  - strategy_config（key-value 当前值）、strategy_config_version（版本历史：version_no/created_at/effective_date/changes/snapshot）
  - select_runs（一次选股运行：run_date/version_id/mode/各级通过数）、select_candidates（候选明细：双通道分数/因子分项 JSON/理由/买入区间/止损/目标）

## 核心约定

1. **PIT 铁律**：基本面只用 `announce_date <= 选股日` 的数据；announce_date 为空视为不可见。
   价格为前复权价（M1 已处理），指标直接计算。
2. **配置版本化**：每次修改生成新版本并记 diff；交易/选股记录关联 version_id；
   新版本默认下一自然日生效（无交易日历的近似）；支持回滚（回滚生成新版本，审计链不断）。
3. **双通道融合**：`final = ai×w1 + engine×w2 ≥ threshold` 入选；双分并存展示；
   AI 失败自动降级为 ENGINE_ONLY，不阻塞选股。
4. **AI 通道真实接线已完成**：`DeepSeekAISignalStrategy` 调 DeepSeek API（OpenAI 兼容，
   `deepseek-chat`，temperature 0.1，超时 30s 重试 1 次），Prompt 模板版本化
   （`resources/prompt/stock-score-v1.txt`）。Key 只从环境变量 `DEEPSEEK_API_KEY` 读取，
   绝不进代码/仓库；无 Key / 超时 / HTTP 错误 / 非 JSON → 抛异常由引擎降级为 ENGINE_ONLY。
   CLI：`--ai-real` 启用真实打分，`--ai-mock` 仍为 Mock 联调。
   部署机上：`export DEEPSEEK_API_KEY=你的Key`（或写进 compose 的 env 文件）。

## 已知限制（诚实）

1. **估值过滤实际不生效**：AKShare 1.19.1 移除了 PE/PB 接口，daily_bars.pe_ttm/pb_mrq 为 NULL
   （M1 README 已知坑 #1）。FundamentalFilter 遇到估值缺失会跳过并记日志，不误杀。
   等 Tushare/基本面周更新补上估值数据后自动生效，无需改代码。
2. **"下一交易日生效"为近似**：用自然日+1，无交易日历；节假日场景下配置可能提前/延后一天生效，
   M6 接入交易日历后修正。
3. **沙箱 Maven 不可用**：出口代理与 Maven wagon 的 HTTPS 隧道不兼容（curl 正常），
   已用 javac + JUnit Console 全量验证（35/35 通过）；`mvn package` 在常规网络下可用。
4. **性能**：行业估值分布按 (行业,日期) 缓存；全市场 5000 只 × 120 根 Bar 为内存计算，
   单机日批处理量级，无压力。
5. **输出非投资建议**：CLI 尾部与看板均需保留该声明。
