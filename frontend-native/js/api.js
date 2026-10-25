/**
 * 原生版 API 层：用 fetch 替代 axios，接口契约与 Vue 版完全一致。
 *
 * 三个约定（与后端约定一一对应，改动前先看 docs/dataset.md 第 9 节）：
 *  1. 后端统一返回 { code, msg, data }，code === 0 才成功；
 *  2. 本文件一次剥壳，调用方直接拿到 data；失败一律抛 Error；
 *  3. 后端端口固定 8080，前端静态页在 5174 上，属跨域请求，
 *     由后端 CorsConfig 放行（已包含 http://127.0.0.1:5174）。
 */
(function (global) {
  'use strict';

  /** 后端基址；如需改端口，在 index.html 之前设置 window.AQI_API_BASE 即可 */
  var API_BASE = global.AQI_API_BASE || 'http://127.0.0.1:8080/api';

  /**
   * 统一的 GET 请求。
   * @param {string} path  以 / 开头的接口路径
   * @param {Object} [params] 查询参数，空串/null/undefined 会被丢弃
   * @returns {Promise<any>} Result.data
   */
  async function request(path, params) {
    var url = API_BASE + path;
    if (params) {
      var qs = Object.keys(params)
        .filter(function (key) {
          var value = params[key];
          return value !== undefined && value !== null && value !== '';
        })
        .map(function (key) {
          return encodeURIComponent(key) + '=' + encodeURIComponent(params[key]);
        })
        .join('&');
      if (qs) {
        url += '?' + qs;
      }
    }

    var response;
    try {
      response = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        // 不携带 Cookie，与后端 allowCredentials=false 保持一致
        credentials: 'omit'
      });
    } catch (e) {
      // fetch 只在网络层失败时 reject；4xx/5xx 仍会 resolve，需自行判断
      throw new Error('无法连接后端服务（' + API_BASE + '），请确认 Spring Boot 已启动');
    }

    var body = null;
    try {
      body = await response.json();
    } catch (e) {
      body = null;
    }

    if (!response.ok) {
      throw new Error((body && body.msg) || ('接口返回 HTTP ' + response.status));
    }
    if (body && body.code === 0) {
      return body.data;
    }
    throw new Error((body && body.msg) || '接口返回异常');
  }

  // ----------------------------------------------------------------
  // 指标元信息：与后端 AirQualityService.METRICS 保持一致
  // ----------------------------------------------------------------

  var METRICS = [
    { key: 'aqi', label: 'AQI', unit: '' },
    { key: 'pm25', label: 'PM2.5', unit: 'μg/m³' },
    { key: 'pm10', label: 'PM10', unit: 'μg/m³' },
    { key: 'so2', label: 'SO₂', unit: 'μg/m³' },
    { key: 'no2', label: 'NO₂', unit: 'μg/m³' },
    { key: 'co', label: 'CO', unit: 'mg/m³' },
    { key: 'o3', label: 'O₃', unit: 'μg/m³' }
  ];

  /** 指标键 -> 元信息；未知键兜底为 AQI，避免后端报错前前端先炸 */
  function getMetricMeta(key) {
    for (var i = 0; i < METRICS.length; i++) {
      if (METRICS[i].key === key) return METRICS[i];
    }
    return METRICS[0];
  }

  // ----------------------------------------------------------------
  // AQI 六档等级元信息（HJ 633-2012，配色取自 docs/dataset.md 4.3）
  // textColor 用于等级标签上的文字：亮色背景配深字，暗色背景配白字
  // ----------------------------------------------------------------

  var AQI_LEVELS = [
    { level: 1, label: '优', min: 0, max: 50, color: '#00E400', textColor: '#1f2937' },
    { level: 2, label: '良', min: 51, max: 100, color: '#FFFF00', textColor: '#1f2937' },
    { level: 3, label: '轻度污染', min: 101, max: 150, color: '#FF7E00', textColor: '#1f2937' },
    { level: 4, label: '中度污染', min: 151, max: 200, color: '#FF0000', textColor: '#ffffff' },
    { level: 5, label: '重度污染', min: 201, max: 300, color: '#99004C', textColor: '#ffffff' },
    { level: 6, label: '严重污染', min: 301, max: Infinity, color: '#7E0023', textColor: '#ffffff' }
  ];

  /** AQI 数值 -> 等级元信息（含配色）。异常值兜底为"无数据" */
  function getLevelMeta(aqi) {
    if (aqi === null || aqi === undefined || isNaN(Number(aqi))) {
      return { level: 0, label: '无数据', color: '#9ca3af', textColor: '#ffffff' };
    }
    var value = Number(aqi);
    for (var i = 0; i < AQI_LEVELS.length; i++) {
      if (value >= AQI_LEVELS[i].min && value <= AQI_LEVELS[i].max) return AQI_LEVELS[i];
    }
    return AQI_LEVELS[AQI_LEVELS.length - 1];
  }

  /** 等级编号 -> 等级元信息。
   *
   * 注意与 getLevelMeta 的区别：那个入参是 AQI 数值，这个是等级编号。
   * 混用会把 level=3 当成 AQI=3，错判成"优"。
   */
  function getLevelMetaByLevel(level) {
    for (var i = 0; i < AQI_LEVELS.length; i++) {
      if (AQI_LEVELS[i].level === Number(level)) return AQI_LEVELS[i];
    }
    return null;
  }

  // ----------------------------------------------------------------
  // 接口方法
  // ----------------------------------------------------------------

  /** 把 { cityIds, metric, start, end } 组装成查询参数；cityIds 用逗号连接 */
  function buildParams(options) {
    var params = { metric: options.metric };
    if (options.cityIds && options.cityIds.length > 0) {
      params.cityIds = options.cityIds.join(',');
    }
    if (options.start) params.start = options.start;
    if (options.end) params.end = options.end;
    return params;
  }

  /** A1：城市列表 [{ code, name, province, region }] */
  function getCities() {
    return request('/cities');
  }

  /** 概览卡：{ cityName, monitorDate, aqi, level, levelLabel, primaryPollutant } */
  function getOverview(cityId) {
    return request('/aqi/overview', { cityId: cityId });
  }

  /** 数据集元信息：{ minDate, maxDate, metrics, defaultMetric } */
  function getMeta() {
    return request('/air-quality/meta');
  }

  /** A2：明细分页查询 -> { total, page, size, list } */
  function queryAirQuality(options) {
    var params = buildParams(options);
    params.page = options.page || 1;
    params.size = options.size || 10;
    return request('/air-quality', params);
  }

  /** A3：分组统计 -> [{ code, city, avg, max, min, goodRatio, goodDays, samples, levelCounts }] */
  function queryStats(options) {
    return request('/air-quality/stats', buildParams(options));
  }

  /** 多城市趋势序列 -> { metric, metricLabel, unit, dates, series } */
  function querySeries(options) {
    return request('/air-quality/series', buildParams(options));
  }

  /** A4：单条记录详情 */
  function getRecord(id) {
    return request('/air-quality/' + encodeURIComponent(id));
  }

  // ----------------------------------------------------------------
  // 展示辅助
  // ----------------------------------------------------------------

  /** 数值 + 单位；缺测统一显示为 "—" */
  function formatValue(value, unit) {
    if (value === null || value === undefined || value === '') return '—';
    return unit ? value + ' ' + unit : String(value);
  }

  /** 首要污染物：后端在 AQI <= 50 时不返回该字段，统一显示为"无" */
  function formatPrimary(pollutant) {
    return pollutant || '无';
  }

  global.Api = {
    API_BASE: API_BASE,
    METRICS: METRICS,
    AQI_LEVELS: AQI_LEVELS,
    getMetricMeta: getMetricMeta,
    getLevelMeta: getLevelMeta,
    getLevelMetaByLevel: getLevelMetaByLevel,
    formatValue: formatValue,
    formatPrimary: formatPrimary,
    getCities: getCities,
    getOverview: getOverview,
    getMeta: getMeta,
    queryAirQuality: queryAirQuality,
    queryStats: queryStats,
    querySeries: querySeries,
    getRecord: getRecord
  };
})(window);
