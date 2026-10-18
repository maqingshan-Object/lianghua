# quant-backtest（M4：回测验证模块）

纯 Java 21，依赖 quant-engine + quant-paper，不引入 Spring。
职责：历史回放 → 绩效指标 → 沪深300基准对比 → 单文件 HTML 报告。

## 模块结构

```
quant-backtest/src/main/java/com/quant/backtest/
├── model/   BacktestResult（全部指标容器；数字只来自真实计算）
├── engine/
│   ├── BacktestRunner   回放：在 [from,to] 逐日调用 M3 PaperTrader.runDay
│   │                     （复用撮合/费用/风控，不重写）；回放前锁定策略版本
│   ├── MetricsCalculator 指标：收益率/回撤/夏普/胜率/盈亏比/持仓天数/
│   │                     持有5·10·20天收益分布/样本内8:2外切片
│   └── BenchmarkService 沪深300（000300.SH）基准曲线（交易日对齐，缺失顺延）
├── report/  HtmlReportGenerator（单文件 HTML：ECharts 图 + 文字版表格降级）
└── cli/     BacktestCommand（命令行入口）
```

## 构建与运行

```bash
cd ~/workspace/quant/backend
bash build-backtest.sh test   # 编译 + 组装 fat jar + 跑全部单测（12 个）

# 回测（在源库的临时拷贝上回放，不污染线上 paper_* 模拟账户）
java -jar quant-backtest/target/quant-backtest-1.0.0.jar \
  --db ~/workspace/quant/data/quant.db \
  --from 2024-01-01 --to 2024-12-31 \
  --strategy-config-version 1 \
  --out report-2024.html [--ai-mock] [--initial-capital 100000] [--synthetic]
```

CLI 参数：`--db/--from/--to/--strategy-config-version/--out` 必填；
`--ai-mock`（AI 通道 Mock）、`--initial-capital`（覆盖初始资金）、
`--synthetic`（合成数据验证，报告明确标注）。

沙箱说明同 M2/M3：Maven 跑不通，用 `build-backtest.sh`（javac + libs/ + 手工 fat jar）；
`pom.xml` 保留给正常网络环境（`mvn package`）。

## 报告内容

单文件 HTML（ECharts CDN，有网即可看图；无网/加载失败自动降级为文字版表格）：

1. 基本信息：区间 / 交易日天数 / 初始资金 / 锁定版本 / 成交笔数 / 基准说明
2. 核心绩效指标：总收益率 / 年化（250交易日/年）/ 最大回撤 / 夏普（无风险利率=0）/
   胜率 / 盈亏比 / 平均持仓天数 / 沪深300同期收益 / 超额收益
3. 样本内/外（按时间 8:2，样本外不参与调参）
4. 图表：净值曲线（策略 vs 沪深300）、回撤曲线、持有 5/10/20 天收益分布直方图
5. 逐笔交易明细（含理由、版本）
6. 风控拦截统计（停开新仓天数、作废挂单数、拦截原因）
7. 假设声明区：滑点/费率/T+1/成交逻辑/数据区间/样本划分，逐条列出
8. 免责声明：不构成投资建议；历史≠未来；模拟盈利≠实盘盈利

## 核心约定

1. **版本锁定**：回放前把 `--strategy-config-version` 的快照存为新版本
   （effective_date=from），全区间同一版本；回放后校验版本数未变。
2. **不污染线上**：回放在源库的临时拷贝上执行，`paper_*` 的 reset 只发生在拷贝库。
3. **费用口径**：报告指标均为含费用口径（佣金/印花税/滑点）；单测已验证费用开启时收益严格低于裸收益。
4. **数字诚实**：报告里的每个数字都来自回放记录的真实计算；无数据时显示"—"，
   不编造；合成数据运行时报告与日志明确标注。

## 已知限制（诚实）

1. **沙箱无真实行情**：回放逻辑已用合成数据验证（12/12 单测 + 端到端 CLI 冒烟）；
   真实回测需在部署机（有 M1 历史数据后）跑，命令见上。
2. **基准依赖 000300.SH**：库中无沪深300 日线时不做基准对比，报告明确标注。
3. **AI 通道仍为 Mock**（沿用 M2/M3）：`--ai-mock` 启用；真实 DeepSeek 接线等 Ma 提供 Key。
4. **样本外切片是展示口径**：本回测全程使用锁定版本、未做参数寻优；
   若以后做参数扫描，必须用样本外做最终确认（防过拟合）。
5. **输出非投资建议**：报告页脚与 CLI 均保留声明。
