<template>
  <div class="header">
    <h1>📈 量化选股 · 每日决策台</h1>
    <div class="sub">A股日线 · AI与引擎双通道打分 · 模拟交易 · 仅供参考，不构成投资建议</div>
  </div>

  <component
    :is="views[tab]"
    :symbol="openSymbol"
    @open-stock="openStock"
  />

  <nav class="tabs">
    <button v-for="t in tabs" :key="t.id" :class="{active: tab === t.id}" @click="tab = t.id">
      <span class="ico">{{ t.icon }}</span><span>{{ t.label }}</span>
    </button>
  </nav>
</template>

<script setup>
import { ref } from 'vue'
import TodayView from './views/TodayView.vue'
import StockDetailView from './views/StockDetailView.vue'
import BacktestView from './views/BacktestView.vue'
import PaperView from './views/PaperView.vue'
import ConfigView from './views/ConfigView.vue'

const tab = ref('today')
const openSymbol = ref('')
const views = { today: TodayView, stock: StockDetailView, backtest: BacktestView, paper: PaperView, config: ConfigView }
const tabs = [
  { id: 'today', label: '今日选股', icon: '📊' },
  { id: 'stock', label: '个股', icon: '🔍' },
  { id: 'backtest', label: '回测', icon: '🧪' },
  { id: 'paper', label: '模拟盘', icon: '💼' },
  { id: 'config', label: '配置', icon: '⚙️' },
]

function openStock(symbol) {
  openSymbol.value = symbol
  tab.value = 'stock'
}
</script>
