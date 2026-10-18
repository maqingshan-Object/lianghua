<template>
  <div ref="el" class="chart tall"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { ref, onMounted, onUnmounted, watch } from 'vue'

const props = defineProps({
  bars: Array,          // [{date, open, high, low, close, volume}]
  ma: Object,           // {ma5:[], ma10:[], ma20:[], ma60:[]}
  signals: Array,        // [{date, price, text}]
  buyLow: Number, buyHigh: Number, stopLoss: Number,
})

const el = ref(null)
let chart = null

function render() {
  if (!chart || !props.bars?.length) return
  const dates = props.bars.map(b => b.date)
  const ohlc = props.bars.map(b => [b.open, b.close, b.low, b.high])
  const vols = props.bars.map(b => b.volume)
  const maLine = (arr, color, name) => ({
    name, type: 'line', data: arr, smooth: true, showSymbol: false,
    lineStyle: { width: 1, color }, emphasis: { disabled: true },
  })
  const markPoints = (props.signals || []).map(s => ({
    coord: [s.date, s.price], value: s.text,
    itemStyle: { color: '#f5a623' }, symbolSize: 8,
    label: { formatter: '{c}', fontSize: 10, color: '#b97a0a', position: 'top' },
  }))
  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    legend: { data: ['K线', 'MA5', 'MA10', 'MA20', 'MA60'], top: 0, textStyle: { fontSize: 11 } },
    grid: [
      { left: '12%', right: '6%', top: '12%', height: '52%' },
      { left: '12%', right: '6%', top: '68%', height: '20%' },
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { show: false }, axisTick: { show: false } },
      { type: 'category', data: dates, gridIndex: 1, axisLabel: { fontSize: 10 } },
    ],
    yAxis: [
      { scale: true, gridIndex: 0, splitNumber: 4, axisLabel: { fontSize: 10 } },
      { scale: true, gridIndex: 1, axisLabel: { fontSize: 10, formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], start: 55, end: 100 }],
    series: [
      {
        name: 'K线', type: 'candlestick', data: ohlc,
        itemStyle: { color: '#e5484d', color0: '#18a058', borderColor: '#e5484d', borderColor0: '#18a058' },
        markPoint: { data: markPoints, symbol: 'pin' },
        markLine: {
          silent: true, symbol: 'none',
          data: [{ yAxis: props.stopLoss, name: '止损线', lineStyle: { color: '#e5484d', type: 'dashed' }, label: { formatter: '止损 {c}', fontSize: 10 } }],
        },
        markArea: {
          silent: true,
          data: [[{ yAxis: props.buyLow, itemStyle: { color: 'rgba(47,111,237,.10)' } }, { yAxis: props.buyHigh }]],
          label: { show: true, formatter: '买入区间', fontSize: 10, color: '#2f6fed', position: 'insideTopRight' },
        },
      },
      maLine(props.ma.ma5, '#f5a623', 'MA5'),
      maLine(props.ma.ma10, '#2f6fed', 'MA10'),
      maLine(props.ma.ma20, '#9b59b6', 'MA20'),
      maLine(props.ma.ma60, '#18a058', 'MA60'),
      { name: '成交量', type: 'bar', data: vols, xAxisIndex: 1, yAxisIndex: 1, itemStyle: { color: '#a8b3c7' } },
    ],
  }, true)
}

function onResize() { chart && chart.resize() }
onMounted(() => { chart = echarts.init(el.value); render(); window.addEventListener('resize', onResize) })
onUnmounted(() => { window.removeEventListener('resize', onResize); chart && chart.dispose() })
watch(() => [props.bars, props.ma], render, { deep: true })
</script>
