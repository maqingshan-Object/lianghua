<template>
  <div ref="el" class="chart" style="height:220px"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { ref, onMounted, onUnmounted, watch } from 'vue'

// 收益分布柱状图
const props = defineProps({ data: Array }) // [{range, count}]

const el = ref(null)
let chart = null

function render() {
  if (!chart || !props.data?.length) return
  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: '10%', right: '6%', top: '10%', bottom: '18%' },
    xAxis: { type: 'category', data: props.data.map(d => d.range), axisLabel: { fontSize: 10, interval: 0, rotate: 20 } },
    yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
    series: [{
      type: 'bar',
      data: props.data.map(d => ({
        value: d.count,
        itemStyle: { color: d.range.includes('-') ? '#18a058' : '#e5484d' },
      })),
      label: { show: true, position: 'top', fontSize: 10 },
    }],
  }, true)
}

function onResize() { chart && chart.resize() }
onMounted(() => { chart = echarts.init(el.value); render(); window.addEventListener('resize', onResize) })
onUnmounted(() => { window.removeEventListener('resize', onResize); chart && chart.dispose() })
watch(() => props.data, render, { deep: true })
</script>
