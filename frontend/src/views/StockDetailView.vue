<template>
  <div class="page">
    <div class="card">
      <div class="row" style="gap:8px">
        <input v-model="query" placeholder="输入股票代码或名称，如 002475 / 立讯精密" @keyup.enter="search" style="flex:1" />
        <button class="btn" @click="search" style="width:auto">分析</button>
      </div>
    </div>

    <div v-if="d">
      <div class="card">
        <div class="between">
          <div><b style="font-size:16px">{{ d.name }}</b> <span class="muted">{{ d.symbol }} · {{ d.industry }}</span></div>
          <div><b class="num" :class="d.chg >= 0 ? 'up' : 'down'" style="font-size:18px">{{ d.price }}</b>
            <span :class="d.chg >= 0 ? 'up' : 'down'" class="num"> {{ d.chg >= 0 ? '+' : '' }}{{ d.chg }}%</span></div>
        </div>
        <KlineChart :bars="d.bars" :ma="d.ma" :signals="d.signals" :buy-low="d.buy_low" :buy-high="d.buy_high" :stop-loss="d.fundamentals ? stopLossVal : 0" />
        <div class="muted">K线含均线/成交量/信号触发点；蓝色区域为买入区间，红色虚线为止损线</div>
      </div>

      <div class="card">
        <h3>基本面</h3>
        <div class="grid4">
          <div class="stat"><div class="v num">{{ d.fundamentals.pe }}</div><div class="k">PE（行业{{ d.fundamentals.pe_industry_pct }}分位）</div></div>
          <div class="stat"><div class="v num">{{ d.fundamentals.pb }}</div><div class="k">PB（行业{{ d.fundamentals.pb_industry_pct }}分位）</div></div>
          <div class="stat"><div class="v num">{{ d.fundamentals.roe }}</div><div class="k">ROE</div></div>
          <div class="stat"><div class="v num">{{ d.fundamentals.debt_ratio }}</div><div class="k">资产负债率</div></div>
          <div class="stat"><div class="v num">{{ d.fundamentals.revenue_yoy }}</div><div class="k">营收增速</div></div>
          <div class="stat"><div class="v num">{{ d.fundamentals.profit_yoy }}</div><div class="k">净利润增速</div></div>
        </div>
      </div>

      <div class="card">
        <h3>评分拆解</h3>
        <div class="scores" style="display:flex;gap:8px;margin-bottom:10px">
          <div class="score-box"><div class="v num" style="color:var(--blue)">{{ d.final_score }}</div><div class="k">综合</div></div>
          <div class="score-box"><div class="v num">{{ d.ai_score }}</div><div class="k">AI</div></div>
          <div class="score-box"><div class="v num">{{ d.engine_score }}</div><div class="k">引擎</div></div>
        </div>
        <div v-for="f in d.score_breakdown" :key="f.name" class="factor">
          <div class="row"><span>{{ f.name }}</span><b class="num">{{ f.score }}分</b></div>
          <div class="bar"><i :style="{width: f.score + '%'}"></i></div>
        </div>
      </div>

      <div class="card">
        <h3>结论 <span :class="d.conclusion.buyable ? 'badge ok' : 'badge warn'">{{ d.conclusion.buyable ? '符合买入条件' : '暂不符合买入条件' }}</span></h3>
        <div class="reason" v-for="(r, i) in d.conclusion.reasons" :key="i">{{ r }}</div>
        <h3 style="margin-top:10px">风险点</h3>
        <div class="row" style="flex-wrap:wrap;gap:6px">
          <span class="badge danger" v-for="rk in d.conclusion.risks" :key="rk">{{ rk }}</span>
        </div>
      </div>
    </div>
    <div v-else class="card muted" style="text-align:center;padding:30px">输入代码或名称，查看同一套规则的个股分析</div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { api } from '../api/index.js'
import KlineChart from '../components/KlineChart.vue'

const props = defineProps({ symbol: String })
const query = ref('')
const d = ref(null)
const stopLossVal = computed(() => d.value?.stop_loss ?? 0)

async function search() {
  const q = query.value.trim()
  if (!q) return
  // mock 下按名称/代码模糊匹配候选表
  const list = await api.candidates()
  const hit = list.find(c => c.symbol.startsWith(q.toUpperCase()) || c.name.includes(q))
  d.value = await api.stockDetail(hit ? hit.symbol : list[0].symbol)
  query.value = hit ? hit.symbol : ''
}
if (props.symbol) { query.value = props.symbol; search() }
watch(() => props.symbol, (s) => { if (s) { query.value = s; search() } })
</script>
