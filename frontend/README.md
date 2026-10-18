# M5b 前端（Vue 3 + ECharts）

量化选股每日决策台：5 个页面（今日选股 / 个股详情 / 回测中心 / 模拟交易看板 / 策略配置），移动端优先自适应。

## 目录

```
frontend/
├── index.html
├── vite.config.js
├── package.json
├── src/
│   ├── main.js            # 入口
│   ├── styles.css         # 全局样式（移动端优先）
│   ├── App.vue            # 顶部栏 + 底部/顶部导航 + 视图切换
│   ├── api/index.js       # API 层（USE_MOCK 开关）
│   ├── mock/data.js       # mock 数据（固定种子，可复现）
│   ├── components/
│   │   ├── KlineChart.vue   # K线+均线+成交量+信号点+买入区间+止损线
│   │   ├── NetWorthChart.vue# 净值曲线（策略 vs 沪深300）+ 回撤
│   │   └── DistChart.vue    # 收益分布柱状图
│   └── views/
│       ├── TodayView.vue      # 今日选股
│       ├── StockDetailView.vue# 个股详情
│       ├── BacktestView.vue   # 回测中心
│       ├── PaperView.vue      # 模拟交易看板
│       └── ConfigView.vue     # 策略配置
└── dist/                  # 构建产物（部署用）
```

## 运行

```bash
cd ~/workspace/quant/frontend
npm install
npm run dev      # 开发：http://localhost:5173
npm run build    # 构建到 dist/
```

## Mock 与真实 API 切换

- `src/api/index.js` 顶部 `USE_MOCK = true`：当前全部走 `src/mock/data.js`，mock 数据用固定种子生成，每次刷新一致。
- M5a 后端就绪后：将 `USE_MOCK` 改为 `false`，前端走 `/api/*` 真实接口；`vite.config.js` 里 dev 代理已配 `/api → http://localhost:8080`（按实际后端地址改）。
- 契约外补充：`GET /api/paper/networth`（看板净值曲线用，后端联调时可加；mock 已实现）。

## 已知事项

- JS bundle 约 1.1MB（ECharts 全量引入），gzip 后约 387KB；如需优化可按需引入 ECharts 组件。
- 图表在窗口 resize 时自适应；移动端底部导航，桌面端顶部导航。
- 所有金额/分数展示均为 mock 数据，仅供联调，不构成投资建议。
