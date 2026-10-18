<template>
  <div class="page">
    <div class="card">
      <h3>运行回测</h3>
      <div class="grid2">
        <div class="field"><label>策略版本</label>
          <select v-model="version"><option>v5</option><option>v6</option><option>v7</option></select>
        </div>
        <div class="field"><label>说明</label><div class="muted" style="padding:9px 0">同一版本多次运行结果一致</div></div>
        <div class="field"><label>开始日期</label><input v-model="from" type="date" /></div>
        <div class="field"><label>结束日期</label><input v-model="to" type="date" /></div>
      </div>
      <button class="btn" @click="run" :disabled="loading">{{ loading ? '回测中…' : '开始回测' }}</button>
    </div>

    <div v-if="r">
      <div class="card">
        <h3>核心指标 <span class="muted">版本 {{ version }} · {{ from }} ~ {{ to }}</span></h3>
        <div class="grid4">
          <div class="stat"><div class="v num up">{{ r.metrics.total_return }}</div><div class="k">总收益率</div></div>
          <div class="stat"><div class="v num up">{{ r.metrics.annual_return }}</div><div class="k">年化</div></div>
          <div class="stat"><div class="v num down">{{ r.metrics.max_drawdown }}</div><div class="k">最大回撤</div></div>
          <div class="stat"><div class="v num">{{ r.metrics.sharpe }}</div><div class="k">夏普比率</div></div>
          <div class="stat"><div class="v num">{{ r.metrics.win_rate }}</div><div class="k">胜率</div></div>
          <div class="stat"><div class="v num">{{ r.metrics.profit_loss_ratio }}</div><div class="k">盈亏比</div></div>
          <div class="stat"><div class="v num">{{ r.metrics.trades }}</div><div class="k">交易次数</div></div>
          <div class="stat"><div class="v num">{{ r.metrics.avg_hold_days }}天</div><div class="k">平均持仓</div></div>
        </div>
        <div class="kv"><span class="k">持有5天 / 10天 / 20天平均表现</span><span class="num">{{ r.metrics.hold_5d }} / {{ r.metrics.hold_10d }} / {{ r.metrics.hold_20d }}</span></div>
      </div>

      <div class="card">
        <h3>净值曲线 vs 沪深300</h3>
        <NetWorthChart :dates="r.dates" :strategy="r.strategy" :benchmark="r.benchmark" :drawdown="r.drawdown" />
      </div>

      <div class="card">
        <h3>单笔收益分布</h3>
        <DistChart :data="r.distribution" />
      </div>

      <div class="card">
        <h3>逐笔交易明细（{{ r.trades.length }} 笔 · 展示前50）</h3>
        <div class="tbl-wrap"><table class="tbl">
          <thead><tr><th>日期</th><th>标的</th><th>方向</th><th>价格</th><th>数量</th><th>理由</th><th>版本</th><th>盈亏</th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in r.trades.slice(0, 50)" :key="i">
              <td>{{ t.date }}</td><td>{{ t.name }}</td><td>{{ t.dir }}</td>
              <td class="num">{{ t.price }}</td><td class="num">{{ t.qty }}</td>
              <td>{{ t.reason }}</td><td><span class="ver-tag">{{ t.version }}</span></td>
              <td class="num" :class="String(t.pnl).startsWith('-') ? 'down' : 'up'">{{ t.pnl }}</td>
            </tr>
          </tbody>
        </table></div>
      </div>

      <div class="card"><h3>假设声明</h3><div class="muted">{{ r.assumptions }}</div></div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { api } from '../api/index.js'
import NetWorthChart from '../components/NetWorthChart.vue'
import DistChart from '../components/DistChart.vue'

const version = ref('v7')
const from = ref('2025-10-20')
const to = ref('2026-10-19')
const loading = ref(false)
const r = ref(null)

async function run() {
  loading.value = true
  try { r.value = await api.runBacktest({ from: from.value, to: to.value, version: version.value }) }
  finally { loading.value = false }
}
</script>
