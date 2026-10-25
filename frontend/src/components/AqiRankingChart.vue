<template>
  <!--
    任务 16：城市 AQI 排行榜（横向条形图）。
    后端已按 AQI 升序返回，这里反转数组让"最干净的城市"显示在最上方
    （ECharts 类目轴自下而上排列，index 0 在最底部）。
  -->
  <div class="chart-box">
    <div ref="chartRef" class="chart"></div>
    <div v-if="!items || items.length === 0" class="mask">暂无排行数据</div>
    <div v-else-if="loading" class="mask">加载中…</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getLevelMeta, formatPrimary } from '../api/air.js'

const props = defineProps({
  /** 后端返回的排行榜（AQI 升序） */
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

const chartRef = ref(null)
let chart = null
let observer = null

function buildOption() {
  // 反转 -> 最优城市排在图表顶部；数据项用对象形式逐条指定颜色
  const rows = [...props.items].reverse().map((item) => {
    const meta = getLevelMeta(item.aqi)
    return {
      value: item.aqi,
      name: item.cityName,
      itemStyle: { color: meta.color, borderRadius: [0, 4, 4, 0] }
    }
  })

  return {
    grid: { left: 72, right: 72, top: 16, bottom: 16 },
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const item = [...props.items].reverse()[params.dataIndex]
        if (!item) return ''
        return [
          `<b>${item.cityName}</b>`,
          `AQI <b>${item.aqi}</b>（${item.levelLabel}）`,
          `首要污染物 ${formatPrimary(item.primaryPollutant)}`
        ].join('<br/>')
      }
    },
    xAxis: { type: 'value', name: 'AQI', nameTextStyle: { fontSize: 11, color: '#6b7280' } },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.name),
      axisTick: { show: false },
      axisLine: { show: false }
    },
    series: [
      {
        type: 'bar',
        data: rows,
        barWidth: '60%',
        // 条尾显示 AQI 数值，避免还要对照 X 轴读数
        label: {
          show: true,
          position: 'right',
          formatter: (p) => `${p.value}`,
          fontSize: 11,
          color: '#374151'
        }
      }
    ]
  }
}

function render() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

function resize() {
  if (chart) chart.resize()
}

onMounted(() => {
  chart = echarts.init(chartRef.value)
  render()
  window.addEventListener('resize', resize)
  if (window.ResizeObserver) {
    observer = new ResizeObserver(() => resize())
    observer.observe(chartRef.value)
  }
})

watch(() => props.items, async () => {
  await nextTick()
  render()
}, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (observer) {
    observer.disconnect()
    observer = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.chart-box {
  position: relative;
  height: 380px;
}

.chart {
  width: 100%;
  height: 100%;
}

.mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.7);
  color: var(--text-muted);
  font-size: 13px;
}
</style>
