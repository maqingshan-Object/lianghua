# 量化交易程序 v1（A股日线 · 每日选股 + 模拟交易）

每天收盘后自动扫描 A 股全市场，按"基础过滤 → 基本面 → 技术面"三层规则打分，
选出 5–10 只候选股；10 万元虚拟资金每日自动调仓做模拟交易；每条规则都经过
历史回测验证。第一版不上实盘、不构成投资建议。

## 架构

```
collector/          Python 数据采集（AKShare 主源 + baostock 交叉校验，SQLite 增量更新）
backend/
  quant-engine/     Java 选股引擎（三层过滤、四策略可插拔、AI/引擎双通道融合打分）
  quant-paper/      Java 模拟交易（T+1 撮合、风控、每笔强制理由 + 策略版本）
  quant-backtest/   Java 回测（复用撮合逻辑、沪深300基准、HTML 报告）
  quant-api/        Spring Boot API（13 个接口，托管前端）
frontend/           Vue 3 + ECharts（今日选股 / 个股详情 / 回测中心 / 模拟看板 / 策略配置）
deploy/             Docker 一键部署、cloudflared 临时隧道、每日任务与备份脚本
```

## 里程碑

M1 数据采集 → M2 选股引擎 → M3 模拟交易 → M4 回测验证 → M5 前后端 → M6 部署准备，
另有 AI 打分通道（DeepSeek）真实接线。

## 部署

见 `deploy/DEPLOY_CHECKLIST.md`，约 1 小时（Docker + 临时隧道，无需买域名）。
DeepSeek Key 只走环境变量 `DEEPSEEK_API_KEY`，绝不进代码。

## 免责

本程序所有输出仅为量化研究参考，不构成投资建议。实盘有风险，决策请自主做出。
