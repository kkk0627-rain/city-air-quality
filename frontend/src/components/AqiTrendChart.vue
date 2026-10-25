<template>
  <!--
    任务 26：多城市趋势折线图（F4 + F5）。
    相比旧版（单城市、固定画 AQI）的两处改动：
      1. 支持多城市 —— 每个城市一条曲线，配 12 色轮换调色板；
      2. 支持指标切换 —— 曲线数值由父组件传入的 metric 决定，
         单位直接显示在 Y 轴名称上，切换时无需改本组件。
  -->
  <div class="chart-box">
    <div ref="chartRef" class="chart"></div>
    <p v-if="isEmpty" class="empty">当前筛选条件下没有数据</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getMetricMeta } from '../api/airQuality.js'

const props = defineProps({
  /** { metric, metricLabel, unit, dates: string[], series: [{ code, city, values: [] }] } */
  data: { type: Object, default: () => ({ dates: [], series: [] }) },
  loading: { type: Boolean, default: false }
})

const chartRef = ref(null)
let chart = null
let observer = null

/** 12 城轮换调色板：相邻色相拉开，避免多条折线难以区分 */
const LINE_COLORS = [
  '#2563eb', '#dc2626', '#059669', '#d97706',
  '#7c3aed', '#0891b2', '#db2777', '#65a30d',
  '#ea580c', '#4f46e5', '#0d9488', '#9333ea'
]

const dates = computed(() => props.data.dates || [])
const series = computed(() => props.data.series || [])
const meta = computed(() => getMetricMeta(props.data.metric || 'aqi'))
const isEmpty = computed(() => !props.loading && series.value.length === 0)

function buildOption() {
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' }
    },
    legend: {
      top: 0,
      type: 'scroll',
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { fontSize: 12, color: '#4b5563' }
    },
    grid: { left: 8, right: 20, top: 46, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: dates.value,
      boundaryGap: false,
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
    series: series.value.map((item, index) => ({
      name: item.city,
      type: 'line',
      smooth: true,
      showSymbol: false,
      // 缺测日期为 null，此处必须断线，否则会把空洞连成虚假趋势
      connectNulls: false,
      lineStyle: { width: 2, color: LINE_COLORS[index % LINE_COLORS.length] },
      itemStyle: { color: LINE_COLORS[index % LINE_COLORS.length] },
      data: (item.values || []).map((value) => (value === null ? null : Number(value)))
    }))
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

watch(() => props.data, render, { deep: true })
</script>

<style scoped>
.chart-box {
  position: relative;
  width: 100%;
}

.chart {
  width: 100%;
  height: 340px;
}

.empty {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: #9ca3af;
}
</style>
