import axios from 'axios'

/**
 * 任务 23：需求 A2~A4 的前端 API 层。
 *
 * 说明（为什么要新建这个文件、以及那点刻意的重复）：
 *  1. 既有 `air.js` 已通过验收，被三个既有组件依赖，因此**不改动它**；
 *  2. 本文件自带一个 axios 实例，与 `air.js` 的约 15 行样板是刻意的重复——
 *     换取的是"新增接口零回归风险"。等 API 模块超过 3 个时，
 *     再抽 `src/api/http.js` 统一收口，这是显而易见的清理点；
 *  3. 城市列表只有一个来源（`/api/cities`），这里从 `air.js` 转出，
 *     让看板页面只需从本文件 import。
 */

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 响应拦截：剥掉 Result 外壳，只把 data 交给调用方
request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code === 0) {
      return body.data
    }
    return Promise.reject(new Error((body && body.msg) || '接口返回异常'))
  },
  (error) => {
    const msg = (error.response && error.response.data && error.response.data.msg) ||
      error.message || '网络请求失败'
    return Promise.reject(new Error(msg))
  }
)

// ------------------------------------------------------------------
// 指标元信息：与后端 AirQualityService.METRICS 保持一致
// key 用于接口参数，label 用于切换按钮，unit 用于坐标轴与表格表头
// ------------------------------------------------------------------

export const METRICS = [
  { key: 'aqi', label: 'AQI', unit: '' },
  { key: 'pm25', label: 'PM2.5', unit: 'μg/m³' },
  { key: 'pm10', label: 'PM10', unit: 'μg/m³' },
  { key: 'so2', label: 'SO₂', unit: 'μg/m³' },
  { key: 'no2', label: 'NO₂', unit: 'μg/m³' },
  { key: 'co', label: 'CO', unit: 'mg/m³' },
  { key: 'o3', label: 'O₃', unit: 'μg/m³' }
]

/** 指标键 -> 元信息；未知键兜底为 AQI，避免后端报错前前端先炸 */
export function getMetricMeta(key) {
  return METRICS.find((item) => item.key === key) || METRICS[0]
}

// ------------------------------------------------------------------
// 接口方法
// ------------------------------------------------------------------

/** A1：城市列表 [{ code, name, province, region }]（复用 air.js 的既有方法） */
export { getCities } from './air.js'

/** A2：明细分页查询 -> { total, page, size, list } */
export function queryAirQuality({ cityIds = [], metric = 'aqi', start = '', end = '', page = 1, size = 10 } = {}) {
  const params = { metric, page, size }
  if (cityIds && cityIds.length > 0) {
    params.cityIds = cityIds.join(',')
  }
  if (start) {
    params.start = start
  }
  if (end) {
    params.end = end
  }
  return request.get('/air-quality', { params })
}

/** A3：分组统计 -> [{ code, city, avg, max, min, goodRatio, goodDays, samples, levelCounts }] */
export function queryStats({ cityIds = [], metric = 'aqi', start = '', end = '' } = {}) {
  const params = { metric }
  if (cityIds && cityIds.length > 0) {
    params.cityIds = cityIds.join(',')
  }
  if (start) {
    params.start = start
  }
  if (end) {
    params.end = end
  }
  return request.get('/air-quality/stats', { params })
}

/** 多城市趋势序列 -> { metric, metricLabel, unit, dates, series } */
export function querySeries({ cityIds = [], metric = 'aqi', start = '', end = '' } = {}) {
  const params = { metric }
  if (cityIds && cityIds.length > 0) {
    params.cityIds = cityIds.join(',')
  }
  if (start) {
    params.start = start
  }
  if (end) {
    params.end = end
  }
  return request.get('/air-quality/series', { params })
}

/** A4：单条记录详情 */
export function getRecord(id) {
  return request.get(`/air-quality/${id}`)
}

// ------------------------------------------------------------------
// 展示辅助
// ------------------------------------------------------------------

/** 六档等级配色（转自 air.js，供新组件直接引用） */
export { AQI_LEVELS, getLevelMeta } from './air.js'

/** 数据集元信息 -> { minDate, maxDate, metrics, defaultMetric } */
export function getMeta() {
  return request.get('/air-quality/meta')
}

/** 数值 + 单位；缺测统一显示为 "—" */
export function formatValue(value, unit = '') {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return unit ? `${value} ${unit}` : String(value)
}
