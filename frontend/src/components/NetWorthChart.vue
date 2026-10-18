<template>
  <div ref="el" class="chart"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { ref, onMounted, onUnmounted, watch } from 'vue'

// 净值曲线（策略 vs 基准）+ 回撤
const props = defineProps({
  dates: Array, strategy: Array, benchmark: Array, drawdown: Array,
  title: { type: String, default: '' },
})

const el = ref(null)
let chart = null

function render() {
  if (!chart || !props.dates?.length) return
  chart.setOption({
    animation: false,
    title: props.title ? { text: props.title, left: 'center', textStyle: { fontSize: 13 } } : undefined,
    tooltip: { trigger: 'axis' },
    legend: { data: ['策略净值', '沪深300', '回撤%'], top: props.title ? 22 : 0, textStyle: { fontSize: 11 } },
    grid: { left: '10%', right: '6%', top: props.title ? '18%' : '14%', bottom: '12%' },
    xAxis: { type: 'category', data: props.dates, axisLabel: { fontSize: 10 } },
    yAxis: [
      { type: 'value', scale: true, axisLabel: { fontSize: 10 } },
      { type: 'value', axisLabel: { fontSize: 10, formatter: '{value}%' } },
    ],
    dataZoom: [{ type: 'inside', start: 0, end: 100 }],
    series: [
      { name: '策略净值', type: 'line', data: props.strategy, showSymbol: false, lineStyle: { width: 2, color: '#e5484d' } },
      { name: '沪深300', type: 'line', data: props.benchmark, showSymbol: false, lineStyle: { width: 1.5, color: '#8a8f9e', type: 'dashed' } },
      { name: '回撤%', type: 'line', data: props.drawdown, yAxisIndex: 1, showSymbol: false, lineStyle: { width: 1, color: '#2f6fed' }, areaStyle: { color: 'rgba(47,111,237,.12)' } },
    ],
  }, true)
}

function onResize() { chart && chart.resize() }
onMounted(() => { chart = echarts.init(el.value); render(); window.addEventListener('resize', onResize) })
onUnmounted(() => { window.removeEventListener('resize', onResize); chart && chart.dispose() })
watch(() => [props.dates, props.strategy], render, { deep: true })
</script>
