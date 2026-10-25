<template>
  <!--
    任务 25：空气质量等级分布环形图（F7）。
    入参是 A3 统计接口返回的数组，这里把各城市的 levelCounts 累加成
    所选范围内的整体分布，再用六档国标配色渲染。
  -->
  <div class="chart-box">
    <div ref="chartRef" class="chart"></div>
    <p v-if="isEmpty" class="empty">当前筛选条件下没有数据</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { AQI_LEVELS } from '../api/airQuality.js'

const props = defineProps({
  /** A3 返回的统计数组 [{ code, city, avg, max, min, goodRatio, goodDays, samples, levelCounts }] */
  stats: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

const chartRef = ref(null)
let chart = null
let observer = null

/** 各档天数累加结果：[{ level, label, color, days }] */
const distribution = computed(() => AQI_LEVELS.map((meta) => ({
  ...meta,
  days: props.stats.reduce((sum, row) => {
    const counts = row.levelCounts || {}
    return sum + (Number(counts[meta.level]) || 0)
  }, 0)
})))

const totalDays = computed(() => distribution.value.reduce((sum, item) => sum + item.days, 0))
const isEmpty = computed(() => !props.loading && totalDays.value === 0)

const seriesData = computed(() => distribution.value
  .filter((item) => item.days > 0)
  .map((item) => ({
    name: item.label,
    value: item.days,
    itemStyle: { color: item.color }
  })))

function buildOption() {
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}<br/>${p.value} 天（${p.percent}%）`
    },
    legend: {
      bottom: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { fontSize: 12, color: '#4b5563' }
    },
    series: [{
      type: 'pie',
      radius: ['46%', '70%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
      label: { show: false },
      emphasis: {
        label: { show: true, fontSize: 13, fontWeight: 500, formatter: '{b}\n{c} 天' }
      },
      data: seriesData.value
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
