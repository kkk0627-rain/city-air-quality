<template>
  <!--
    任务 26：城市对比柱状图（F6）。
    数据源同样是 A3 统计接口，y 轴为所选指标在所选时段内的均值。
  -->
  <div class="chart-box">
    <div ref="chartRef" class="chart"></div>
    <p v-if="isEmpty" class="empty">当前筛选条件下没有数据</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { AQI_LEVELS, getMetricMeta } from '../api/airQuality.js'

const props = defineProps({
  /** A3 返回的统计数组 */
  stats: { type: Array, default: () => [] },
  /** 当前指标键，用于取单位与展示名 */
  metric: { type: String, default: 'aqi' },
  loading: { type: Boolean, default: false }
})

const chartRef = ref(null)
let chart = null
let observer = null

const meta = computed(() => getMetricMeta(props.metric))

/** 按均值升序：最干净的排在最左，与排行榜的阅读习惯一致 */
const sorted = computed(() => [...props.stats].sort((a, b) => {
  const av = a.avg === null || a.avg === undefined ? Number.MAX_VALUE : Number(a.avg)
  const bv = b.avg === null || b.avg === undefined ? Number.MAX_VALUE : Number(b.avg)
  return av - bv
}))

const isEmpty = computed(() => !props.loading && sorted.value.length === 0)

/**
 * 柱子配色取该城市在时段内的「众数等级」（levelCounts 最大的一档），
 * 而不是按均值反推等级——后者对 PM2.5 这类浓度值没有意义
 * （levelOf 的分段是 AQI 标度，不是 μg/m³）。
 */
function dominantLevel(row) {
  const counts = row.levelCounts || {}
  let best = 1
  let bestCount = -1
  for (const [level, count] of Object.entries(counts)) {
    const value = Number(count) || 0
    if (value > bestCount) {
      bestCount = value
      best = Number(level)
    }
  }
  return best
}

function buildOption() {
  const palette = Object.fromEntries(AQI_LEVELS.map((item) => [item.level, item.color]))
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const p = params[0]
        const row = sorted.value[p.dataIndex]
        const unit = meta.value.unit ? ` ${meta.value.unit}` : ''
        return `${row.city}<br/>均值 ${p.value}${unit}<br/>最大 ${row.max ?? '—'} · 最小 ${row.min ?? '—'}<br/>优良率 ${row.goodRatio ?? '—'}%`
      }
    },
    grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: sorted.value.map((row) => row.city),
      axisLabel: { fontSize: 12, color: '#4b5563' },
      axisLine: { lineStyle: { color: '#d1d5db' } }
    },
    yAxis: {
      type: 'value',
      name: meta.value.unit,
      nameTextStyle: { fontSize: 11, color: '#6b7280' },
      axisLabel: { fontSize: 12, color: '#4b5563' },
      splitLine: { lineStyle: { color: '#f1f0ec' } }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 34,
      data: sorted.value.map((row) => ({
        value: row.avg === null || row.avg === undefined ? 0 : Number(row.avg),
        itemStyle: { color: palette[dominantLevel(row)] || '#9ca3af' }
      }))
    }]
  }
}

function render() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

function handleResize() {
  if (chart) chart.resize()
}

onMounted(async () => {
  await nextTick()
  chart = echarts.init(chartRef.value)
  render()
  observer = new ResizeObserver(handleResize)
  observer.observe(chartRef.value)
})

onBeforeUnmount(() => {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(() => props.stats, render, { deep: true })
</script>

<style scoped>
.chart-box {
  position: relative;
  width: 100%;
}

.chart {
  width: 100%;
  height: 320px;
}

.empty {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: #9ca3af;
}
</style>
