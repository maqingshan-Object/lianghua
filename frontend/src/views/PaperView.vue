<template>
  <div class="page">
    <div class="card" v-if="s">
      <h3>账户总览 <span class="muted">{{ s.date }} · 策略版本 <span class="ver-tag">{{ s.version }}</span></span></h3>
      <div class="grid4">
        <div class="stat"><div class="v num">{{ fmtMoney(s.total_assets) }}</div><div class="k">总资产</div></div>
        <div class="stat"><div class="v num">{{ fmtMoney(s.cash) }}</div><div class="k">现金</div></div>
        <div class="stat"><div class="v num">{{ fmtMoney(s.market_value) }}</div><div class="k">持仓市值</div></div>
        <div class="stat"><div class="v num" :class="s.cum_pnl >= 0 ? 'up' : 'down'">{{ s.cum_return }}</div><div class="k">累计收益({{ fmtMoney(s.cum_pnl) }})</div></div>
      </div>
      <div class="kv"><span class="k">今日盈亏</span><span class="num" :class="s.today_pnl >= 0 ? 'up' : 'down'">{{ s.today_pnl >= 0 ? '+' : '' }}{{ fmtMoney(s.today_pnl) }}（{{ s.today_return }}）</span></div>
      <NetWorthChart v-if="nw" :dates="nw.dates" :strategy="nw.strategy" :benchmark="nw.benchmark" title="模拟盘净值" />
    </div>

    <div class="card">
      <h3>持仓（{{ positions.length }}）</h3>
      <div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>标的</th><th>数量</th><th>成本</th><th>现价</th><th>盈亏</th><th>离止损</th><th>离目标</th></tr></thead>
        <tbody>
          <tr v-for="p in positions" :key="p.symbol" :class="p.warn ? 'warn-row' : ''">
            <td>{{ p.name }}<br /><span class="muted">{{ p.symbol }}</span></td>
            <td class="num">{{ p.qty }}</td><td class="num">{{ p.cost }}</td><td class="num">{{ p.price }}</td>
            <td class="num" :class="p.pnl >= 0 ? 'up' : 'down'">{{ p.pnl >= 0 ? '+' : '' }}{{ p.pnl }}<br />({{ p.pnl_pct }})</td>
            <td class="num" :class="p.warn ? 'down' : ''">{{ p.dist_stop }}</td>
            <td class="num">{{ p.dist_target }}</td>
          </tr>
        </tbody>
      </table></div>
      <div class="muted" style="margin-top:6px">黄色高亮：接近止损/需关注的持仓</div>
    </div>

    <div class="card">
      <h3>交易记录</h3>
      <div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>日期</th><th>标的</th><th>方向</th><th>价格</th><th>数量</th><th>买卖理由</th><th>版本</th><th>盈亏</th></tr></thead>
        <tbody>
          <tr v-for="(t, i) in trades" :key="i">
            <td>{{ t.date }}</td><td>{{ t.name }}</td>
            <td><span :class="t.dir === '买入' ? 'badge ok' : 'badge warn'">{{ t.dir }}</span></td>
            <td class="num">{{ t.price }}</td><td class="num">{{ t.qty }}</td>
            <td style="white-space:normal;min-width:160px">{{ t.reason }}</td>
            <td><span class="ver-tag">{{ t.version }}</span></td>
            <td class="num" :class="String(t.pnl).startsWith('-') ? 'down' : 'up'">{{ t.pnl }}</td>
          </tr>
        </tbody>
      </table></div>
    </div>
    <div class="card muted" style="text-align:center">虚拟资金模拟交易 · 不构成投资建议</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api/index.js'
import NetWorthChart from '../components/NetWorthChart.vue'

const s = ref(null), positions = ref([]), trades = ref([]), nw = ref(null)
const fmtMoney = (n) => '¥' + Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

onMounted(async () => {
  const [a, b, c, d] = await Promise.all([api.paperSummary(), api.paperPositions(), api.paperTrades(), api.paperNetWorth()])
  s.value = a; positions.value = b; trades.value = c
  nw.value = { dates: d.dates, strategy: d.strategy, benchmark: d.benchmark }
})
</script>
