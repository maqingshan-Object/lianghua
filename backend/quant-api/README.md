# quant-api（M5a：Spring Boot 后端 API）

Spring Boot 3.2.5 + JDK 21。纯展示/配置层：所有接口**只读** SQLite 行情与模拟账户；
写操作仅限策略配置版本表（`strategy_config` / `strategy_config_version`）。
**零券商/交易 API 依赖**，编译期就不具备实盘能力。

## 模块结构

```
quant-api/src/main/java/com/quant/api/
├── QuantApiApplication.java      启动入口（@EnableScheduling）
├── config/
│   ├── ApiProperties.java        app.* 配置（库路径/前端目录/采集脚本/报告目录/AI mock/定时任务）
│   ├── JacksonConfig.java        LocalDate 序列化为 yyyy-MM-dd
│   └── StaticConfig.java         托管 ../frontend/dist（M5b 产物；不存在则跳过）
├── service/
│   ├── BaseService.java          每请求独立 SQLite 连接
│   ├── EngineWiring.java         引擎装配（与各模块 CLI 一致）
│   ├── MarketService.java        市场概况
│   ├── CandidateService.java     候选股（读 select_runs/select_candidates）
│   ├── StockService.java         个股详情（K线/均线/信号事件/基本面/打分拆解/结论）
│   ├── PaperService.java         模拟账户总览/持仓/交易记录
│   ├── BacktestService.java      回测（临时拷贝库回放 + HTML 报告）
│   ├── StrategyConfigService.java 配置查询/保存新版本/回滚
│   └── DailyJobService.java      每日任务：采集→选股→模拟调仓（防重入）
└── web/
    ├── *Controller.java          REST 映射
    ├── dto/Dtos.java             响应 DTO
    └── ApiExceptionHandler.java  统一 JSON 错误（不泄露堆栈）
```

## 接口表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 → `{"status":"ok"}` |
| GET | `/api/market/overview` | 市场概况：日期/指数涨跌/涨跌家数/成交额/热门行业 Top5 |
| GET | `/api/candidates?date=` | 候选股（date 缺省取最近一次选股运行）；含双通道分数、因子分项、买卖理由、买入区间/止损/目标、`histWinRate`（该标的模拟已平仓胜率，无记录为 null） |
| GET | `/api/stock/{symbol}?date=` | 个股详情：近 120 根 K 线、MA5/10/20/60、近 60 日信号事件、基本面（PIT）、单股打分拆解、规则结论 |
| GET | `/api/paper/summary` | 模拟账户总览：总资产/现金/持仓市值/累计盈亏/今日盈亏/近 90 日净值曲线 |
| GET | `/api/paper/positions` | 模拟持仓：成本/现价/盈亏/止损目标距离、`alert`（STOP/TARGET/NEAR_STOP） |
| GET | `/api/paper/trades?limit=` | 模拟交易记录（倒序，默认 200，上限 1000）；`fee`=佣金+印花税 |
| POST | `/api/backtest` | 运行回测。Body `{"from":"2024-01-01","to":"2024-12-31","version":1}`；在源库**临时拷贝**上回放，不污染线上模拟账户；同步返回，**长区间可能耗时数分钟** |
| GET | `/api/backtest/report/{file}` | 下载回测 HTML 报告（文件名白名单校验，防路径穿越） |
| GET | `/api/strategy/config` | 当前全量配置 + 版本历史 |
| PUT | `/api/strategy/config` | 保存新版本。Body `{"changes":{"fusion.threshold":"55"},"note":"..."}`；未知 key 直接 400；默认下一自然日生效 |
| POST | `/api/strategy/config/rollback` | 回滚。Body `{"versionNo":1}`（生成新版本，审计链不断） |
| POST | `/api/jobs/daily` | 手动触发每日任务：调 Python 采集脚本 → 取最新交易日 → `PaperTrader.runDay`；防重入 |

`/`：前端 dist 存在时返回 `index.html`，否则返回 API 状态 JSON。

## 构建与运行

**常规网络**（Ma 的 Mac / 部署机）：

```bash
cd ~/workspace/quant/backend
mvn -pl quant-api -am package -DskipTests
# 运行（fat jar 由 spring-boot repackage 产出，或直接用 target/classes + 依赖）
java -jar quant-api/target/quant-api-1.0.0.jar --app.db-path=~/workspace/quant/data/quant.db
```

**本沙箱**（Maven 跑不通，Spring Boot jar 已用 curl 拉到 `backend/libs-spring/`）：

```bash
cd ~/workspace/quant/backend
bash build-api.sh        # 编译（依赖 quant-engine/paper/backtest 的 target/classes，先跑它们的构建脚本）
bash build-api.sh run    # 编译 + 前台启动（:8080，Ctrl+C 停止）
```

启动参数（覆盖 `application.yml` 的 `app.*`）：

```bash
java -cp ... com.quant.api.QuantApiApplication \
  --app.db-path=/data/quant.db \
  --app.frontend-dist=/opt/frontend/dist \
  --app.ai-mock=true
```

## 每日任务（生产 cron，推荐方式）

```cron
# 交易日 16:35 触发每日任务（采集→选股→模拟调仓）；非交易日 runDay 自行跳过
35 16 * * 1-5  curl -s -m 600 -X POST http://localhost:8080/api/jobs/daily >> ~/workspace/quant/backend/logs/daily-job-cron.log 2>&1
# 每周日 20:00 基本面全量 + 校验（调 M1 脚本）
0 20 * * 0  ~/workspace/quant/venv/bin/python ~/workspace/quant/collector/collect.py --full --crosscheck --with-stocks --db ~/workspace/quant/data/quant.db >> ~/workspace/quant/collector/cron.log 2>&1
```

也可用内置 `@Scheduled`：`app.daily-job.enabled=true`（默认 false）+ `app.daily-job.cron`
（默认 `0 30 16 * * ?`）。采集子进程超时默认 30 分钟（`app.daily-job.collector-timeout-minutes`），
超时会被终止并记为 `timeout`。

## 已验证（沙箱实测）

用 `/tmp/api-test.db`（3 只标的 × 30 天合成行情）逐个接口冒烟验证通过：

- `/api/health`、`/api/market/overview`（指数/涨跌家数/成交额/热门行业）、
  `/api/candidates`（双通道分数、因子分项、`histWinRate`）、
  `/api/stock/600519.SH`（120 根 K 线、MA 序列、基本面、单股打分拆解、规则结论；未知标的 400）、
  `/api/paper/summary|positions|trades`（净值曲线、止损目标距离与预警）、
  `/api/strategy/config`（GET/PUT 新版本 v2 次日生效/未知 key 400/rollback 生成 v3）、
  `POST /api/backtest`（15 天回放、指标 JSON、基准对比、HTML 报告下载）、
  `/`（dist 存在时返回前端 index.html）。
- `POST /api/jobs/daily`：ProcessBuilder 拉起采集脚本（参数/日志/超时/防重入）；用模拟失败的采集脚本验证：
  采集失败时如实返回 `collector.status=failed`（含 exitCode 与日志尾），随后照常执行 paper 腿
  （`PaperTrader.runDay`，当日净值/版本均正确），整链路返回 `status=ok`。

## 已知限制（诚实）

1. **沙箱未跑 `mvn package`**：`pom.xml` 完整（Spring Boot 3.2.5 / Spring 6.1.6 / Jackson 2.17.1 /
   Tomcat 10.1.20，含本次补下的 `spring-jcl`），但沙箱 Maven 与出口代理不兼容，
   实际用 `javac + libs-spring/` 编译验证通过。部署机上有正常网络时 `mvn package` 即可。
2. **回测为同步接口**：长区间（一年全市场）回放可能耗时数分钟，调用方请设足超时；
   报告落盘在 `app.reports-dir`，通过 `/api/backtest/report/{file}` 下载。
3. **"下一交易日生效"为近似**：沿用 M2 约定（自然日+1，无交易日历），M6 接入交易日历后修正。
4. **AI 通道**：`app.ai-mock=true` 用 Mock 联调；`app.ai-mock=false`（默认）切真实 DeepSeek
   打分（Key 从环境变量 `DEEPSEEK_API_KEY` 读取；部署机上 `export DEEPSEEK_API_KEY=你的Key`；
   无 Key / 超时 / 报错时自动降级为纯引擎分，不阻塞选股）。Key 绝不进代码与仓库。
5. **SQLite 并发**：每请求独立连接；读多写少场景够用。回测在临时拷贝库上跑，不阻塞线上读写。
6. **输出非投资建议**：所有分数/结论均为规则计算结果，仅供研究。
