import axios from 'axios'

/**
 * 任务 13：看板前端的 API 层 与 AQI 等级元信息。
 *
 * 两个职责放在同一个文件里，是为了守住"一个任务只改一个文件"的约束：
 *  1. axios 实例 + 四个接口方法；
 *  2. 六档等级配色（dataset.md 4.3），供概览卡/趋势图/排行榜三个组件复用，
 *     避免组件各写一份导致颜色不一致。
 *
 * 请求路径：baseURL 用相对路径 /api，由 vite.config.js 代理到后端 8080。
 * 响应结构：后端统一返回 { code, msg, data }，拦截器在这里一次剥壳，
 * 调用方直接拿到 data；业务失败抛出 Error，由组件统一处理。
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
// 四个接口（契约见 docs/dataset.md 第 9 节）
// ------------------------------------------------------------------

/** 城市列表：[{ id, cityName, province, region }] */
export const getCities = () => request.get('/cities')

/** 概览卡：{ cityName, monitorDate, aqi, level, levelLabel, primaryPollutant } */
export const getOverview = (cityId) => request.get('/aqi/overview', { params: { cityId } })

/** 趋势图：[{ date, aqi, pm25, pm10, o3 }]；range = 7d | 30d | 12m */
export const getTrend = (cityId, range = '30d') =>
  request.get('/aqi/trend', { params: { cityId, range } })

/** 排行榜：[{ cityId, cityName, aqi, level, levelLabel, primaryPollutant }]，按 AQI 升序 */
export const getRanking = () => request.get('/aqi/ranking')

// ------------------------------------------------------------------
// AQI 六档等级元信息（HJ 633-2012，配色取自 dataset.md 4.3）
// textColor 用于等级标签上的文字：亮色背景配深字，暗色背景配白字
// ------------------------------------------------------------------

export const AQI_LEVELS = [
  { level: 1, label: '优', min: 0, max: 50, color: '#00E400', textColor: '#1f2937' },
  { level: 2, label: '良', min: 51, max: 100, color: '#FFFF00', textColor: '#1f2937' },
  { level: 3, label: '轻度污染', min: 101, max: 150, color: '#FF7E00', textColor: '#1f2937' },
  { level: 4, label: '中度污染', min: 151, max: 200, color: '#FF0000', textColor: '#ffffff' },
  { level: 5, label: '重度污染', min: 201, max: 300, color: '#99004C', textColor: '#ffffff' },
  { level: 6, label: '严重污染', min: 301, max: Infinity, color: '#7E0023', textColor: '#ffffff' }
]

/** AQI -> 等级元信息（含配色）。异常值兜底为最高档 */
export function getLevelMeta(aqi) {
  if (aqi === null || aqi === undefined || Number.isNaN(Number(aqi))) {
    return { level: 0, label: '无数据', color: '#9ca3af', textColor: '#ffffff' }
  }
  const value = Number(aqi)
  return AQI_LEVELS.find((item) => value >= item.min && value <= item.max) || AQI_LEVELS[5]
}

/** 首要污染物：后端在 AQI <= 50 时不返回该字段，统一显示为"无" */
export function formatPrimary(pollutant) {
  return pollutant || '无'
}

/** 按等级给图表 X 轴/图形底色一个浅色版本，避免大面积高饱和块 */
export function levelBg(level) {
  const found = AQI_LEVELS.find((item) => item.level === level)
  return found ? found.color : '#9ca3af'
}
