// API 层
// TODO: M5a 后端就绪后，将 USE_MOCK 改为 false，走真实 /api 接口。
// 后端 API 契约见任务说明；mock 数据仅用于前端联调。
const USE_MOCK = true

import * as mock from '../mock/data.js'

async function get(path) {
  if (USE_MOCK) return mock.resolve(path)
  const r = await fetch(path)
  if (!r.ok) throw new Error(`GET ${path} failed: ${r.status}`)
  return r.json()
}

async function post(path, body) {
  if (USE_MOCK) return mock.resolve(path, body)
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  if (!r.ok) throw new Error(`POST ${path} failed: ${r.status}`)
  return r.json()
}

async function put(path, body) {
  if (USE_MOCK) return mock.resolve(path, body, 'PUT')
  const r = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  if (!r.ok) throw new Error(`PUT ${path} failed: ${r.status}`)
  return r.json()
}

export const api = {
  marketOverview: () => get('/api/market/overview'),
  candidates: (date) => get(`/api/candidates?date=${date || ''}`),
  stockDetail: (symbol, date) => get(`/api/stock/${symbol}?date=${date || ''}`),
  runBacktest: (params) => post('/api/backtest', params),
  paperSummary: () => get('/api/paper/summary'),
  paperPositions: () => get('/api/paper/positions'),
  paperTrades: () => get('/api/paper/trades'),
  paperNetWorth: () => get('/api/paper/networth'), // 扩展：看板净值曲线（契约外补充，联调时后端可加）
  getConfig: () => get('/api/strategy/config'),
  saveConfig: (cfg) => put('/api/strategy/config', cfg),
  rollbackConfig: (version) => post('/api/strategy/config/rollback', { version }),
}
