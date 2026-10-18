// Mock 数据：前端联调使用。后端 M5a 就绪后，api/index.js 中 USE_MOCK=false 即切换。
// 为保证每次刷新数据一致，使用固定种子随机数。

function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rand = mulberry32(20261019)
const pick = (arr) => arr[Math.floor(rand() * arr.length)]
const fmt = (n, d = 2) => Number(n).toFixed(d)

// ---------- 市场概况 ----------
export function marketOverview() {
  return {
    date: '2026-10-19',
    indices: [
      { name: '上证指数', value: 3124.56, chg: 0.82 },
      { name: '深证成指', value: 10234.12, chg: 1.15 },
      { name: '创业板指', value: 2089.34, chg: 1.68 },
      { name: '沪深300', value: 3654.21, chg: 0.95 },
    ],
    advance: 3421, decline: 1523, flat: 89,
    amount: 1.24, // 万亿元
    hot_industries: [
      { name: '半导体', chg: 3.21 }, { name: 'AI算力', chg: 2.87 },
      { name: '新能源', chg: 2.45 }, { name: '消费电子', chg: 1.96 },
      { name: '医药', chg: 1.32 },
    ],
  }
}

// ---------- 今日候选 ----------
const CANDIDATES = [
  { symbol: '002475.SZ', name: '立讯精密', industry: '消费电子', final_score: 82, ai_score: 88, engine_score: 76, signal: '突破',
    reason: '站上60日线，近5日成交量放大2.1倍，突破近60日高点38.2元，PE处于行业30%分位', buy_low: 37.6, buy_high: 38.4, stop_loss: 34.9, target_price: 43.5, hist_win_rate: 68.4,
    factors: [['趋势', 85], ['突破', 92], ['放量', 78], ['估值', 61], ['业绩', 70]] },
  { symbol: '300750.SZ', name: '宁德时代', industry: '新能源', final_score: 79, ai_score: 74, engine_score: 84, signal: '趋势',
    reason: '5/10/20/60日均线多头排列，MACD金叉，Q3净利润增速41%，经营现金流为正', buy_low: 186.0, buy_high: 190.5, stop_loss: 172.0, target_price: 215.0, hist_win_rate: 64.2,
    factors: [['趋势', 90], ['突破', 66], ['放量', 72], ['估值', 58], ['业绩', 88]] },
  { symbol: '688981.SH', name: '中芯国际', industry: '半导体', final_score: 77, ai_score: 91, engine_score: 63, signal: '放量',
    reason: 'AI打分91：国产替代逻辑强化，机构研报上调目标价；技术面放量上涨但乖离率偏大，注意追高风险', buy_low: 52.3, buy_high: 54.0, stop_loss: 48.5, target_price: 61.0, hist_win_rate: 59.8,
    factors: [['趋势', 70], ['突破', 58], ['放量', 95], ['估值', 42], ['业绩', 66]] },
  { symbol: '600036.SH', name: '招商银行', industry: '银行', final_score: 74, ai_score: 68, engine_score: 80, signal: '企稳',
    reason: '回调至60日线企稳，连续3日缩量止跌，股息率5.2%，PB处于历史15%分位', buy_low: 33.8, buy_high: 34.5, stop_loss: 32.0, target_price: 38.0, hist_win_rate: 71.5,
    factors: [['趋势', 62], ['突破', 40], ['放量', 55], ['估值', 92], ['业绩', 78]] },
  { symbol: '002230.SZ', name: '科大讯飞', industry: 'AI应用', final_score: 72, ai_score: 83, engine_score: 61, signal: '突破',
    reason: '突破整理平台，成交量温和放大，大模型C端付费用户环比+35%，但PE偏高', buy_low: 41.2, buy_high: 42.6, stop_loss: 38.0, target_price: 48.0, hist_win_rate: 57.3,
    factors: [['趋势', 68], ['突破', 84], ['放量', 70], ['估值', 35], ['业绩', 74]] },
  { symbol: '600900.SH', name: '长江电力', industry: '公用事业', final_score: 70, ai_score: 62, engine_score: 78, signal: '趋势',
    reason: '长线多头趋势完好，沿20日线稳步上行，来水偏丰预期提升发电量，防御属性强', buy_low: 26.4, buy_high: 26.9, stop_loss: 25.2, target_price: 29.5, hist_win_rate: 73.1,
    factors: [['趋势', 88], ['突破', 52], ['放量', 48], ['估值', 70], ['业绩', 82]] },
  { symbol: '300059.SZ', name: '东方财富', industry: '券商', final_score: 68, ai_score: 71, engine_score: 65, signal: '放量',
    reason: '市场成交额连续3日超万亿，券商板块受益，股价放量站上20日线', buy_low: 21.5, buy_high: 22.1, stop_loss: 20.0, target_price: 24.8, hist_win_rate: 61.9,
    factors: [['趋势', 60], ['突破', 64], ['放量', 82], ['估值', 55], ['业绩', 68]] },
  { symbol: '000858.SZ', name: '五粮液', industry: '白酒', final_score: 66, ai_score: 58, engine_score: 74, signal: '企稳',
    reason: '超跌后在120日线附近企稳，北向资金连续4日净流入，估值修复预期', buy_low: 118.0, buy_high: 121.5, stop_loss: 110.0, target_price: 135.0, hist_win_rate: 66.7,
    factors: [['趋势', 55], ['突破', 38], ['放量', 62], ['估值', 80], ['业绩', 76]] },
]
export function candidates() { return CANDIDATES }

// ---------- 个股详情 ----------
function genBars(symbol, basePrice, n = 150) {
  const r = mulberry32([...symbol].reduce((a, c) => a + c.charCodeAt(0), 7))
  const bars = []
  let price = basePrice * 0.82
  const d = new Date('2026-03-20')
  for (let i = 0; i < n; i++) {
    d.setDate(d.getDate() + 1)
    if (d.getDay() === 0 || d.getDay() === 6) { d.setDate(d.getDate() + (d.getDay() === 0 ? 1 : 2)); }
    const drift = 0.0012 + Math.sin(i / 18) * 0.002
    const chg = drift + (r() - 0.5) * 0.036
    const open = price
    const close = price * (1 + chg)
    const high = Math.max(open, close) * (1 + r() * 0.012)
    const low = Math.min(open, close) * (1 - r() * 0.012)
    const volume = Math.round(800000 * (1 + Math.abs(chg) * 40) * (0.6 + r()))
    bars.push({
      date: d.toISOString().slice(0, 10),
      open: +fmt(open), high: +fmt(high), low: +fmt(low), close: +fmt(close), volume,
    })
    price = close
  }
  return bars
}

function ma(bars, n) {
  return bars.map((_, i) => {
    if (i < n - 1) return null
    let s = 0
    for (let j = i - n + 1; j <= i; j++) s += bars[j].close
    return +fmt(s / n)
  })
}

export function stockDetail(symbol) {
  const c = CANDIDATES.find((x) => x.symbol === symbol) || CANDIDATES[0]
  const bars = genBars(symbol, (c.buy_low + c.buy_high) / 2)
  const last = bars[bars.length - 1]
  const signals = [
    { date: bars[bars.length - 12].date, price: bars[bars.length - 12].close, text: '放量突破' },
    { date: bars[bars.length - 5].date, price: bars[bars.length - 5].low, text: '回踩企稳' },
  ]
  return {
    symbol: c.symbol, name: c.name, industry: c.industry,
    price: last.close, chg: +fmt(((last.close - bars[bars.length - 2].close) / bars[bars.length - 2].close) * 100),
    bars,
    ma: { ma5: ma(bars, 5), ma10: ma(bars, 10), ma20: ma(bars, 20), ma60: ma(bars, 60) },
    signals,
    buy_low: c.buy_low, buy_high: c.buy_high, stop_loss: c.stop_loss,
    fundamentals: {
      pe: fmt(18 + rand() * 25), pe_industry_pct: Math.round(20 + rand() * 40) + '%',
      pb: fmt(1.5 + rand() * 4), pb_industry_pct: Math.round(15 + rand() * 45) + '%',
      roe: fmt(8 + rand() * 14) + '%', revenue_yoy: fmt(5 + rand() * 30) + '%',
      profit_yoy: fmt(-5 + rand() * 45) + '%', debt_ratio: fmt(25 + rand() * 40) + '%',
    },
    score_breakdown: c.factors.map(([name, score]) => ({ name, score })),
    ai_score: c.ai_score, engine_score: c.engine_score, final_score: c.final_score,
    conclusion: {
      buyable: c.final_score >= 65,
      reasons: [c.reason, `AI评分${c.ai_score}分，引擎评分${c.engine_score}分${c.ai_score > c.engine_score ? '，AI更看好' : c.ai_score < c.engine_score ? '，引擎更看好' : '，两者一致'}`],
      risks: ['大盘系统性回调风险', '放量后追高注意回撤', '财报季业绩不及预期风险'].slice(0, 2 + Math.floor(rand() * 2)),
    },
  }
}

// ---------- 回测 ----------
export function backtestResult(params) {
  const r = mulberry32(99)
  const n = 250
  const dates = []
  const strat = []
  const bench = []
  let s = 1, b = 1
  const d = new Date('2025-10-20')
  for (let i = 0; i < n; i++) {
    d.setDate(d.getDate() + 1)
    if (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + (d.getDay() === 0 ? 1 : 2))
    s *= 1 + 0.0011 + (r() - 0.48) * 0.028
    b *= 1 + 0.0004 + (r() - 0.49) * 0.022
    dates.push(d.toISOString().slice(0, 10))
    strat.push(+fmt(s, 4)); bench.push(+fmt(b, 4))
  }
  // 回撤
  let peak = -Infinity
  const dd = strat.map((v) => { peak = Math.max(peak, v); return +fmt(((v - peak) / peak) * 100) })
  const trades = []
  const names = ['立讯精密', '宁德时代', '中芯国际', '招商银行', '科大讯飞', '长江电力', '东方财富', '五粮液']
  for (let i = 0; i < 28; i++) {
    const win = r() > 0.38
    const pnl = win ? r() * 18 : -r() * 9
    trades.push({
      date: dates[Math.floor(r() * n)],
      symbol: '60' + String(1000 + Math.floor(r() * 8999)) + '.SH',
      name: pick(names), dir: '买入',
      price: fmt(10 + r() * 180), qty: 100 * (1 + Math.floor(r() * 20)),
      reason: pick(['均线多头+放量突破', '创60日新高', '回调60日线企稳', 'AI评分>80入选']),
      version: 'v' + (5 + Math.floor(r() * 3)),
      pnl: fmt(pnl) + '%',
    })
  }
  trades.sort((a, b) => a.date < b.date ? -1 : 1)
  return {
    metrics: {
      total_return: fmt((s - 1) * 100) + '%', annual_return: fmt((s - 1) * 100 * 1.15) + '%',
      max_drawdown: fmt(Math.min(...dd)) + '%', sharpe: fmt(1.1 + r() * 0.8),
      win_rate: '62.4%', profit_loss_ratio: fmt(1.8 + r()), trades: 128, avg_hold_days: 9.4,
      hold_5d: '+2.1%', hold_10d: '+3.4%', hold_20d: '+5.2%',
    },
    dates, strategy: strat, benchmark: bench, drawdown: dd,
    distribution: [
      { range: '<-10%', count: 6 }, { range: '-10%~-5%', count: 14 }, { range: '-5%~0', count: 32 },
      { range: '0~5%', count: 41 }, { range: '5%~10%', count: 22 }, { range: '>10%', count: 13 },
    ],
    trades,
    assumptions: '滑点双边各0.1% / 佣金万2.5（5元保底）/ 印花税卖出千分之一 / T+1 / 整手 / 信号T日收盘产生、T+1开盘成交',
  }
}

// ---------- 模拟交易 ----------
export function paperSummary() {
  return {
    date: '2026-10-19', version: 'v7',
    total_assets: 108452.36, cash: 32410.20, market_value: 76042.16,
    cum_return: '+8.45%', cum_pnl: 8452.36, today_pnl: 1236.50, today_return: '+1.15%',
  }
}
export function paperPositions() {
  return [
    { symbol: '002475.SZ', name: '立讯精密', qty: 800, cost: 37.90, price: 39.42, pnl: 1216.00, pnl_pct: '+4.01%', stop_loss: 34.90, target: 43.50, dist_stop: '-11.5%', dist_target: '+10.3%', warn: false },
    { symbol: '600036.SH', name: '招商银行', qty: 1000, cost: 34.10, price: 34.62, pnl: 520.00, pnl_pct: '+1.52%', stop_loss: 32.00, target: 38.00, dist_stop: '-7.6%', dist_target: '+9.8%', warn: false },
    { symbol: '688981.SH', name: '中芯国际', qty: 400, cost: 54.80, price: 52.10, pnl: -1080.00, pnl_pct: '-4.93%', stop_loss: 48.50, target: 61.00, dist_stop: '-6.9%', dist_target: '+17.1%', warn: true },
    { symbol: '300750.SZ', name: '宁德时代', qty: 100, cost: 188.20, price: 192.36, pnl: 416.00, pnl_pct: '+2.21%', stop_loss: 172.00, target: 215.00, dist_stop: '-10.6%', dist_target: '+11.8%', warn: false },
    { symbol: '600900.SH', name: '长江电力', qty: 1200, cost: 26.55, price: 26.88, pnl: 396.00, pnl_pct: '+1.24%', stop_loss: 25.20, target: 29.50, dist_stop: '-6.3%', dist_target: '+9.7%', warn: false },
  ]
}
export function paperTrades() {
  const rows = [
    ['2026-10-19', '002475.SZ', '立讯精密', '买入', 37.90, 800, '融合75分入选：放量突破+AI 88分', 'v7', '持仓中'],
    ['2026-10-16', '688981.SH', '中芯国际', '买入', 54.80, 400, 'AI 91分强烈推荐，引擎63分偏弱，融合77分', 'v7', '-4.93%'],
    ['2026-10-15', '600036.SH', '招商银行', '买入', 34.10, 1000, '回调60日线企稳，引擎80分', 'v6', '+1.52%'],
    ['2026-10-14', '000858.SZ', '五粮液', '卖出', 119.40, 900, '跌破止损位110? 未触发；信号消失止盈', 'v6', '+6.20%'],
    ['2026-10-13', '300750.SZ', '宁德时代', '买入', 188.20, 100, '均线多头排列，融合79分', 'v6', '+2.21%'],
    ['2026-10-12', '600900.SH', '长江电力', '买入', 26.55, 1200, '趋势88分，长线多头', 'v6', '+1.24%'],
    ['2026-10-10', '002230.SZ', '科大讯飞', '卖出', 40.10, 500, '触发止损-8%', 'v5', '-8.00%'],
    ['2026-10-09', '300059.SZ', '东方财富', '卖出', 22.65, 1000, '达到目标位止盈', 'v5', '+11.30%'],
  ]
  return rows.map((t) => ({ date: t[0], symbol: t[1], name: t[2], dir: t[3], price: t[4], qty: t[5], reason: t[6], version: t[7], pnl: t[8] }))
}

// ---------- 策略配置 ----------
export function strategyConfig() {
  return {
    version: 7,
    updated_at: '2026-10-18 16:02',
    mode: 'FUSION',
    ai_weight: 0.5, engine_weight: 0.5, threshold: 65,
    presets: {
      conservative: { label: '保守', ai_weight: 0.3, engine_weight: 0.7, threshold: 75 },
      balanced: { label: '平衡', ai_weight: 0.5, engine_weight: 0.5, threshold: 65 },
      aggressive: { label: '激进', ai_weight: 0.7, engine_weight: 0.3, threshold: 55 },
    },
    filters: { min_list_days: 250, min_amount: 5000, exclude_st: true, exclude_suspended: true, pe_limit: 50, debt_ratio_limit: 70 },
    factor_weights: { trend: 0.30, breakout: 0.25, volume: 0.20, fundamental: 0.25 },
    trade: { max_positions: 8, max_single_pct: 20, max_total_pct: 80, stop_loss_pct: 8, take_profit_pct: 20, initial_capital: 100000 },
    strategies: [
      { id: 'ma_trend', name: '均线多头排列', enabled: true, weight: 1.0 },
      { id: 'breakout', name: '创N日新高突破', enabled: true, weight: 1.0 },
      { id: 'volume', name: '放量', enabled: true, weight: 0.8 },
      { id: 'pullback', name: '回调均线企稳', enabled: true, weight: 0.8 },
      { id: 'ai_score', name: 'AI打分通道', enabled: true, weight: 1.0 },
    ],
    versions: [
      { version: 7, time: '2026-10-18 16:02', summary: '阈值 65→65（不变），AI权重 0.4→0.5', changes: 'ai_weight: 0.4 → 0.5' },
      { version: 6, time: '2026-10-15 16:05', summary: '新增AI打分通道，模式切为融合', changes: 'mode: ENGINE_ONLY → FUSION；新增策略 ai_score' },
      { version: 5, time: '2026-10-12 16:01', summary: '止损 10%→8%，更严格', changes: 'stop_loss_pct: 10 → 8' },
      { version: 4, time: '2026-10-09 15:58', summary: 'PE上限 60→50', changes: 'pe_limit: 60 → 50' },
    ],
  }
}

// ---------- 模拟盘净值（看板曲线用） ----------
export function paperNetWorth() {
  const r = mulberry32(7)
  const n = 120
  const dates = []
  const strat = []
  let s = 1
  const d = new Date('2026-06-22')
  for (let i = 0; i < n; i++) {
    d.setDate(d.getDate() + 1)
    if (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + (d.getDay() === 0 ? 1 : 2))
    s *= 1 + 0.0009 + (r() - 0.47) * 0.026
    dates.push(d.toISOString().slice(0, 10))
    strat.push(+fmt(s, 4))
  }
  return { dates, strategy: strat, benchmark: strat.map((_, i) => +fmt(1 + i * 0.0004 + Math.sin(i / 20) * 0.01, 4)) }
}

// ---------- 路由 ----------
export function resolve(path, body, method) {
  const [p, q] = path.split('?')
  const query = Object.fromEntries(new URLSearchParams(q || ''))
  return new Promise((res) => {
    setTimeout(() => {
      if (p === '/api/market/overview') return res(marketOverview())
      if (p === '/api/candidates') return res(candidates())
      if (p.startsWith('/api/stock/')) return res(stockDetail(p.split('/')[3]))
      if (p === '/api/backtest') return res(backtestResult(body))
      if (p === '/api/paper/summary') return res(paperSummary())
      if (p === '/api/paper/positions') return res(paperPositions())
      if (p === '/api/paper/trades') return res(paperTrades())
      if (p === '/api/paper/networth') return res(paperNetWorth())
      if (p === '/api/strategy/config') {
        if (method === 'PUT') return res({ ok: true, version: 8, message: '已保存，下一交易日生效' })
        return res(strategyConfig())
      }
      if (p === '/api/strategy/config/rollback') return res({ ok: true, version: body.version, message: `已回滚到 v${body.version}，下一交易日生效` })
      res({})
    }, 250)
  })
}
