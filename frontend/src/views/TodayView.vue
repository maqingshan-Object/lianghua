<template>
  <div class="page">
    <div class="card" v-if="m">
      <h3>市场概况 <span class="muted">{{ m.date }}</span></h3>
      <div class="grid4">
        <div class="stat" v-for="idx in m.indices" :key="idx.name">
          <div class="v num" :class="idx.chg >= 0 ? 'up' : 'down'">{{ idx.value.toFixed(2) }}</div>
          <div class="k">{{ idx.name }} {{ idx.chg >= 0 ? '+' : '' }}{{ idx.chg }}%</div>
        </div>
      </div>
      <div class="kv"><span class="k">涨 / 跌 / 平</span><span><b class="up num">{{ m.advance }}</b> / <b class="down num">{{ m.decline }}</b> / {{ m.flat }}</span></div>
      <div class="kv"><span class="k">成交额</span><span class="num">{{ m.amount }} 万亿</span></div>
      <div class="row" style="flex-wrap:wrap;margin-top:8px;gap:6px">
        <span class="badge" v-for="h in m.hot_industries" :key="h.name">{{ h.name }} +{{ h.chg }}%</span>
      </div>
    </div>

    <div class="card">
      <h3>今日候选（{{ filtered.length }}）<span class="muted"> · 数据日期 {{ m?.date }}</span></h3>
      <div class="row" style="gap:6px;margin-bottom:4px">
        <select v-model="sortKey" style="flex:1">
          <option value="final_score">按综合分</option>
          <option value="ai_score">按AI分</option>
          <option value="engine_score">按引擎分</option>
          <option value="hist_win_rate">按历史胜率</option>
        </select>
        <select v-model="filterIndustry" style="flex:1">
          <option value="">全部行业</option>
          <option v-for="i in industries" :key="i" :value="i">{{ i }}</option>
        </select>
        <select v-model="filterSignal" style="flex:1">
          <option value="">全部信号</option>
          <option v-for="s in signals" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>

      <div v-for="(c, i) in filtered" :key="c.symbol" class="card cand" @click="$emit('open-stock', c.symbol)" style="cursor:pointer">
        <div class="between">
          <div><b>{{ c.name }}</b> <span class="muted">{{ c.symbol }}</span></div>
          <div><span class="badge">{{ c.signal }}</span> <span class="muted">#{{ i + 1 }}</span></div>
        </div>
        <div class="scores">
          <div class="score-box"><div class="v num" style="color:var(--blue)">{{ c.final_score }}</div><div class="k">综合分</div></div>
          <div class="score-box"><div class="v num" :style="{color: c.ai_score >= 70 ? 'var(--red)' : 'var(--text)'}">{{ c.ai_score }}</div><div class="k">AI分</div></div>
          <div class="score-box"><div class="v num" :style="{color: c.engine_score >= 70 ? 'var(--green)' : 'var(--text)'}">{{ c.engine_score }}</div><div class="k">引擎分</div></div>
        </div>
        <div class="reason">{{ c.reason }}</div>
        <div class="kv"><span class="k">买入区间</span><span class="num">{{ c.buy_low }} – {{ c.buy_high }}</span></div>
        <div class="kv"><span class="k">止损 / 目标</span><span><b class="down num">{{ c.stop_loss }}</b> / <b class="up num">{{ c.target_price }}</b></span></div>
        <div class="kv"><span class="k">历史胜率</span><span class="num">{{ c.hist_win_rate }}%</span></div>
      </div>
      <div class="muted" style="text-align:center;padding:8px">点击卡片查看个股详情 · 仅供参考，不构成投资建议</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api/index.js'

defineEmits(['open-stock'])
const m = ref(null)
const list = ref([])
const sortKey = ref('final_score')
const filterIndustry = ref('')
const filterSignal = ref('')

const industries = computed(() => [...new Set(list.value.map(c => c.industry))])
const signals = computed(() => [...new Set(list.value.map(c => c.signal))])
const filtered = computed(() => {
  let r = list.value.filter(c =>
    (!filterIndustry.value || c.industry === filterIndustry.value) &&
    (!filterSignal.value || c.signal === filterSignal.value))
  return [...r].sort((a, b) => b[sortKey.value] - a[sortKey.value])
})

onMounted(async () => {
  m.value = await api.marketOverview()
  list.value = await api.candidates(m.value.date)
})
</script>
