<template>
  <!--
    任务 28：看板首页组装（F1）。
    布局：顶部标题栏 / 左侧筛选区 / 右侧主区域。
    数据编排全部在本文件：持有筛选条件，调用 A1~A4 四个接口，把结果分发给子组件。
    子组件一律只负责渲染，不自己发请求。
  -->
  <div class="page">
    <header class="page-header">
      <div>
        <h1>城市空气质量看板</h1>
        <p class="sub">
          数据源：中国环境监测总站 · 2025 全年日均监测值 · 数据截止 {{ range.max || '—' }}
        </p>
      </div>
      <button class="refresh" type="button" :disabled="loading" @click="reloadAll">
        {{ loading ? '加载中…' : '刷新' }}
      </button>
    </header>

    <p v-if="error" class="error-bar">{{ error }}</p>

    <div class="layout">
      <aside class="sidebar">
        <FilterPanel
          v-model="filter"
          :cities="cities"
          :metrics="metrics"
          :range="range"
          :loading="loading"
          :hint="selectionHint"
          :disabled="noCitySelected"
          @search="onSearch"
        />
      </aside>

      <main class="content">
        <section class="kpi-row">
          <div v-for="k in kpis" :key="k.label" class="kpi">
            <span class="kpi-label">{{ k.label }}</span>
            <span class="kpi-value">{{ k.value }}</span>
          </div>
        </section>

        <section v-if="focusCityId !== null" class="card">
          <h2>
            焦点城市
            <span class="hint">{{ overview ? overview.cityName : '—' }} · 最新一日快照</span>
          </h2>
          <CityOverview :overview="overview" :loading="loadingOverview" />
        </section>

        <section class="card">
          <h2>
            {{ trendTitle }}
            <span class="hint">{{ rangeText }}</span>
          </h2>
          <AqiTrendChart :data="seriesData" :loading="loadingSeries" />
        </section>

        <div class="grid-2">
          <section class="card">
            <h2>城市对比<span class="hint">{{ metricMeta.label }} 时段均值</span></h2>
            <CityCompareChart :stats="stats" :metric="filter.metric" :loading="loadingStats" />
          </section>

          <section class="card">
            <h2>等级分布<span class="hint">六档天数占比</span></h2>
            <LevelPieChart :stats="stats" :loading="loadingStats" />
          </section>
        </div>

        <section class="card">
          <h2>数据详情<span class="hint">每页 10 条</span></h2>
          <DataDetailTable
            :page-data="pageData"
            :metric="filter.metric"
            :loading="loadingRecords"
            @page-change="onPageChange"
            @detail="onShowDetail"
          />
        </section>

        <section v-if="detail" class="card">
          <h2>记录详情<span class="hint">ID {{ detail.id }}</span></h2>
          <dl class="detail-grid">
            <div><dt>城市</dt><dd>{{ detail.city }}（{{ detail.code }}）</dd></div>
            <div><dt>日期</dt><dd>{{ detail.date }}</dd></div>
            <div><dt>AQI</dt><dd>{{ detail.aqi }} · {{ detail.levelLabel }}</dd></div>
            <div><dt>首要污染物</dt><dd>{{ detail.primaryPollutant || '无' }}</dd></div>
            <div><dt>PM2.5</dt><dd>{{ fmt(detail.pm25, 'μg/m³') }}</dd></div>
            <div><dt>PM10</dt><dd>{{ fmt(detail.pm10, 'μg/m³') }}</dd></div>
            <div><dt>SO₂</dt><dd>{{ fmt(detail.so2, 'μg/m³') }}</dd></div>
            <div><dt>NO₂</dt><dd>{{ fmt(detail.no2, 'μg/m³') }}</dd></div>
            <div><dt>CO</dt><dd>{{ fmt(detail.co, 'mg/m³') }}</dd></div>
            <div><dt>O₃</dt><dd>{{ fmt(detail.o3, 'μg/m³') }}</dd></div>
          </dl>
          <button class="link" type="button" @click="detail = null">收起</button>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import FilterPanel from './components/FilterPanel.vue'
import CityOverview from './components/CityOverview.vue'
import AqiTrendChart from './components/AqiTrendChart.vue'
import CityCompareChart from './components/CityCompareChart.vue'
import LevelPieChart from './components/LevelPieChart.vue'
import DataDetailTable from './components/DataDetailTable.vue'
import { getOverview } from './api/air.js'
import {
  getCities, queryAirQuality, queryStats, querySeries, getRecord, getMeta, getMetricMeta, METRICS
} from './api/airQuality.js'

/** F8 需求指定：每页 10 条 */
const PAGE_SIZE = 10

const cities = ref([])
const metrics = ref(METRICS)
const range = ref({ min: '', max: '' })

const filter = ref({ cityIds: [], metric: 'aqi', start: '', end: '' })
const page = ref(1)

const stats = ref([])
const seriesData = ref({ dates: [], series: [] })
const pageData = ref({ total: 0, page: 1, size: PAGE_SIZE, list: [] })
const overview = ref(null)
const detail = ref(null)

const loadingBase = ref(false)
const loadingStats = ref(false)
const loadingSeries = ref(false)
const loadingRecords = ref(false)
const loadingOverview = ref(false)
const error = ref('')

const loading = computed(() => loadingBase.value || loadingStats.value ||
  loadingSeries.value || loadingRecords.value || loadingOverview.value)

const metricMeta = computed(() => getMetricMeta(filter.value.metric))
const trendTitle = computed(() => `${metricMeta.value.label} 趋势`)
const rangeText = computed(() => (filter.value.start && filter.value.end
  ? `${filter.value.start} ~ ${filter.value.end}`
  : '全域'))

const noCitySelected = computed(() => filter.value.cityIds.length === 0)
const selectionHint = computed(() => (noCitySelected.value ? '请至少选择一个城市' : ''))

/** 焦点城市 = 选中列表里的第一个，用于单城概览卡 */
const focusCityId = computed(() =>
  (filter.value.cityIds.length > 0 ? filter.value.cityIds[0] : null))

/** 由 A3 统计汇总出的四个概览指标 */
const kpis = computed(() => {
  const rows = stats.value
  const unit = metricMeta.value.unit ? ` ${metricMeta.value.unit}` : ''
  if (rows.length === 0) {
    return [
      { label: '覆盖城市', value: '—' },
      { label: `${metricMeta.value.label} 均值`, value: '—' },
      { label: '优良天数占比', value: '—' },
      { label: '污染最重城市', value: '—' }
    ]
  }

  const avgs = rows
    .map((row) => (row.avg === null || row.avg === undefined ? null : Number(row.avg)))
    .filter((value) => value !== null && !Number.isNaN(value))
  const mean = avgs.length
    ? (avgs.reduce((a, b) => a + b, 0) / avgs.length).toFixed(1) + unit
    : '—'

  const good = rows.reduce((sum, row) => sum + (Number(row.goodDays) || 0), 0)
  const samples = rows.reduce((sum, row) => sum + (Number(row.samples) || 0), 0)
  const ratio = samples > 0 ? `${((good * 100) / samples).toFixed(1)}%` : '—'

  const worst = [...rows]
    .filter((row) => row.avg !== null && row.avg !== undefined)
    .sort((a, b) => Number(b.avg) - Number(a.avg))[0]

  return [
    { label: '覆盖城市', value: `${rows.length} 个` },
    { label: `${metricMeta.value.label} 均值`, value: mean },
    { label: '优良天数占比', value: ratio },
    { label: '污染最重城市', value: worst ? `${worst.city} ${worst.avg}` : '—' }
  ]
})

function fmt(value, unit) {
  return value === null || value === undefined || value === '' ? '—' : `${value} ${unit}`
}

function params() {
  return {
    cityIds: filter.value.cityIds,
    metric: filter.value.metric,
    start: filter.value.start,
    end: filter.value.end
  }
}

/** 加载城市列表与数据集元信息；元信息决定日期控件的边界与默认值 */
async function loadBase() {
  loadingBase.value = true
  try {
    const [list, meta] = await Promise.all([getCities(), getMeta()])
    cities.value = list
    if (meta && meta.metrics && meta.metrics.length > 0) {
      metrics.value = meta.metrics
    }
    range.value = {
      min: (meta && meta.minDate) || '',
      max: (meta && meta.maxDate) || ''
    }
    if (meta && meta.maxDate) {
      filter.value = {
        ...filter.value,
        start: shiftDate(meta.maxDate, -29),
        end: meta.maxDate,
        cityIds: list.map((c) => c.code)
      }
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loadingBase.value = false
  }
}

async function loadStats() {
  loadingStats.value = true
  try {
    stats.value = await queryStats(params())
  } catch (e) {
    stats.value = []
    error.value = e.message
  } finally {
    loadingStats.value = false
  }
}

async function loadSeries() {
  loadingSeries.value = true
  try {
    seriesData.value = await querySeries(params())
  } catch (e) {
    seriesData.value = { dates: [], series: [] }
    error.value = e.message
  } finally {
    loadingSeries.value = false
  }
}

async function loadRecords() {
  loadingRecords.value = true
  try {
    pageData.value = await queryAirQuality({
      ...params(), page: page.value, size: PAGE_SIZE
    })
  } catch (e) {
    pageData.value = { total: 0, page: 1, size: PAGE_SIZE, list: [] }
    error.value = e.message
  } finally {
    loadingRecords.value = false
  }
}

async function loadFocusOverview() {
  if (focusCityId.value === null) {
    overview.value = null
    return
  }
  loadingOverview.value = true
  try {
    overview.value = await getOverview(focusCityId.value)
  } catch (e) {
    overview.value = null
    error.value = e.message
  } finally {
    loadingOverview.value = false
  }
}

async function reloadAll() {
  error.value = ''
  await Promise.all([loadStats(), loadSeries(), loadRecords(), loadFocusOverview()])
}

function onSearch() {
  error.value = ''
  page.value = 1
  reloadAll()
}

function onPageChange(next) {
  page.value = next
}

async function onShowDetail(id) {
  try {
    detail.value = await getRecord(id)
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 日期位移。
 *
 * <b>不能用 toISOString()</b>：它按 UTC 输出，在 UTC+8 环境下会把本地
 * 00:00 推回前一天，导致整个区间偏移一天。这里手工按本地字段拼 yyyy-MM-dd。
 */
function shiftDate(iso, deltaDays) {
  const date = new Date(`${iso}T00:00:00`)
  date.setDate(date.getDate() + deltaDays)
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const result = `${y}-${m}-${d}`
  return range.value.min && result < range.value.min ? range.value.min : result
}

watch(page, () => {
  loadRecords()
})

watch(focusCityId, () => {
  loadFocusOverview()
})

onMounted(async () => {
  await loadBase()
  await reloadAll()
})
</script>

<style>
:root {
  --bg: #f5f7fa;
  --card-bg: #ffffff;
  --text: #1f2937;
  --text-muted: #6b7280;
  --border: #e5e7eb;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
}
</style>

<style scoped>
.page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 24px 48px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

h1 {
  margin: 0 0 6px;
  font-size: 24px;
}

.sub {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
}

.refresh {
  padding: 8px 18px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card-bg);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
}

.refresh:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.error-bar {
  margin-bottom: 16px;
  padding: 10px 14px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
  align-items: start;
}

.sidebar {
  position: sticky;
  top: 24px;
}

.content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
}

.kpi-label {
  font-size: 12px;
  color: var(--text-muted);
}

.kpi-value {
  font-size: 20px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.card {
  padding: 16px 18px;
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
}

.card h2 {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 500;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin: 0 0 8px;
}

.detail-grid dt {
  font-size: 12px;
  color: var(--text-muted);
}

.detail-grid dd {
  margin: 4px 0 0;
  font-size: 14px;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}

@media (max-width: 1000px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .sidebar {
    position: static;
  }

  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .grid-2,
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
