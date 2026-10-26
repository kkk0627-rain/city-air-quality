/**
 * 原生版看板主控：状态编排 + DOM 渲染。
 *
 * 设计取舍：
 *  - 没有虚拟 DOM，因此采用「集中 state + 显式 render」的模式：
 *    所有请求只改 state，改完调用对应的 render*()，避免 DOM 与数据脱节；
 *  - 事件一律用委托绑在容器上（城市/指标是动态生成的），容器本身静态存在于 HTML；
 *  - 每个请求各自维护 busy 计数，用于按钮禁用，避免并发时按钮闪烁；
 *  - Tab 只是显示/隐藏 section，不重建 DOM；切回来时必须 resizeAll()，
 *    否则隐藏期间容器宽度为 0，ECharts 会按 0 宽高渲染成空白。
 */
(function (global) {
  'use strict';

  var Api = global.Api;
  var Charts = global.Charts;

  /** F8 需求指定：每页 10 条 */
  var PAGE_SIZE = 10;

  /** 综合指数的定性分档：用于 KPI 卡配色与顶栏综合评级 */
  var CI_BANDS = [
    { max: 2.2, label: '优', tone: 'kpi-good' },
    { max: 2.8, label: '良', tone: 'kpi-good' },
    { max: 3.4, label: '一般', tone: 'kpi-warn' },
    { max: 4.0, label: '较差', tone: 'kpi-bad' },
    { max: Infinity, label: '差', tone: 'kpi-bad' }
  ];

  function bandOf(ci) {
    if (ci === null || ci === undefined || isNaN(Number(ci))) {
      return { label: '—', tone: 'kpi-slate' };
    }
    var v = Number(ci);
    for (var i = 0; i < CI_BANDS.length; i++) {
      if (v < CI_BANDS[i].max) return CI_BANDS[i];
    }
    return CI_BANDS[CI_BANDS.length - 1];
  }

  // ----------------------------------------------------------------
  // 状态
  // ----------------------------------------------------------------

  var state = {
    cities: [],
    metrics: Api.METRICS.slice(),
    range: { min: '', max: '' },
    filter: { cityIds: [], metric: 'aqi', start: '', end: '' },
    page: 1,
    tab: 'overview',
    stats: [],
    series: { dates: [], series: [] },
    analysis: null,
    profileSort: { key: 'rank', dir: 'asc' },
    pageData: { total: 0, page: 1, size: PAGE_SIZE, list: [] },
    overview: null,
    detail: null,
    busy: 0,
    error: ''
  };

  // ----------------------------------------------------------------
  // DOM 引用与工具
  // ----------------------------------------------------------------

  var el = {};
  var IDS = [
    'dataMax', 'verdictValue', 'btnRefresh', 'errorBar', 'cityCount', 'cityChips',
    'metricChips', 'rangeText', 'startDate', 'endDate', 'btnSearch', 'filterHint',
    'kpiRow', 'tabs', 'focusCard', 'focusHint', 'overviewCard',
    'trendTitle', 'trendRange', 'trendChart', 'trendEmpty',
    'pieChart', 'pieEmpty', 'monthChart', 'monthEmpty',
    'rankChart', 'rankEmpty', 'radarChart', 'radarEmpty', 'radarHint',
    'compareMetric', 'compareHint', 'compareChart', 'compareEmpty',
    'profileTable', 'profileBody',
    'regionChart', 'regionEmpty', 'seasonChart', 'seasonEmpty',
    'pollutantChart', 'pollutantEmpty', 'levelHint', 'levelBarChart', 'levelBarEmpty',
    'heatmapChart', 'heatmapEmpty',
    'thMetric', 'tableBody', 'btnPrev', 'btnNext', 'pageInfo',
    'detailCard', 'detailId', 'detailGrid', 'btnCloseDetail'
  ];

  function cacheDom() {
    IDS.forEach(function (id) { el[id] = document.getElementById(id); });
  }

  /** HTML 转义：城市名/日期均来自数据库，仍做一层防护 */
  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function show(node, visible) {
    if (!node) return;
    if (visible) node.removeAttribute('hidden');
    else node.setAttribute('hidden', 'hidden');
  }

  /** 选中集合用字符串键，避免 code 是数字还是字符串引发的比较陷阱 */
  function selectedKeySet() {
    var set = {};
    state.filter.cityIds.forEach(function (code) { set[String(code)] = true; });
    return set;
  }

  function params() {
    return {
      cityIds: state.filter.cityIds,
      metric: state.filter.metric,
      start: state.filter.start,
      end: state.filter.end
    };
  }

  /**
   * 日期位移。
   *
   * 不能用 toISOString()：它按 UTC 输出，在 UTC+8 环境下会把本地
   * 00:00 推回前一天，导致整个区间偏移一天。这里手工按本地字段拼 yyyy-MM-dd。
   */
  function shiftDate(iso, deltaDays) {
    var date = new Date(iso + 'T00:00:00');
    date.setDate(date.getDate() + deltaDays);
    var result = toISODate(date);
    return (state.range.min && result < state.range.min) ? state.range.min : result;
  }

  function toISODate(date) {
    var y = date.getFullYear();
    var m = String(date.getMonth() + 1);
    var d = String(date.getDate());
    if (m.length < 2) m = '0' + m;
    if (d.length < 2) d = '0' + d;
    return y + '-' + m + '-' + d;
  }

  function num(value) {
    return (value === null || value === undefined || value === '') ? null : Number(value);
  }

  function fmt(value, digits) {
    var v = num(value);
    if (v === null || isNaN(v)) return '—';
    return digits === undefined ? String(v) : v.toFixed(digits);
  }

  // ----------------------------------------------------------------
  // 加载态与错误
  // ----------------------------------------------------------------

  function enterBusy() {
    state.busy++;
    renderBusy();
  }

  function leaveBusy() {
    state.busy--;
    if (state.busy < 0) state.busy = 0;
    renderBusy();
  }

  function renderBusy() {
    if (el.btnRefresh) {
      el.btnRefresh.disabled = state.busy > 0;
      el.btnRefresh.textContent = state.busy > 0 ? '加载中…' : '刷新';
    }
    if (el.btnSearch) {
      el.btnSearch.disabled = state.busy > 0 || state.filter.cityIds.length === 0;
      el.btnSearch.textContent = state.busy > 0 ? '查询中…' : '查询';
    }
  }

  function setError(message) {
    state.error = message || '';
    if (!el.errorBar) return;
    if (state.error) {
      el.errorBar.textContent = state.error;
      show(el.errorBar, true);
    } else {
      show(el.errorBar, false);
    }
  }

  // ----------------------------------------------------------------
  // 数据加载
  // ----------------------------------------------------------------

  /** 加载城市列表与数据集元信息；元信息决定日期控件边界与默认值 */
  async function loadBase() {
    enterBusy();
    try {
      var results = await Promise.all([Api.getCities(), Api.getMeta()]);
      var list = results[0] || [];
      var meta = results[1] || {};

      state.cities = list;
      if (meta.metrics && meta.metrics.length > 0) {
        state.metrics = meta.metrics;
      }
      state.range = { min: meta.minDate || '', max: meta.maxDate || '' };

      if (meta.maxDate) {
        state.filter.start = shiftDate(meta.maxDate, -29);
        state.filter.end = meta.maxDate;
        state.filter.cityIds = list.map(function (city) { return city.code; });
      }

      renderCityChips();
      renderMetricChips();
      renderRangeInputs();
      renderDataMax();
    } catch (e) {
      setError(e.message);
    } finally {
      leaveBusy();
    }
  }

  async function loadStats() {
    enterBusy();
    try {
      state.stats = await Api.queryStats(params());
    } catch (e) {
      state.stats = [];
      setError(e.message);
    } finally {
      leaveBusy();
      renderStats();
    }
  }

  async function loadSeries() {
    enterBusy();
    try {
      state.series = await Api.querySeries(params());
    } catch (e) {
      state.series = { dates: [], series: [] };
      setError(e.message);
    } finally {
      leaveBusy();
      renderSeries();
    }
  }

  async function loadRecords() {
    enterBusy();
    try {
      var options = params();
      options.page = state.page;
      options.size = PAGE_SIZE;
      state.pageData = await Api.queryAirQuality(options);
    } catch (e) {
      state.pageData = { total: 0, page: 1, size: PAGE_SIZE, list: [] };
      setError(e.message);
    } finally {
      leaveBusy();
      renderTable();
    }
  }

  async function loadOverview() {
    if (state.filter.cityIds.length === 0) {
      state.overview = null;
      renderOverview();
      return;
    }
    enterBusy();
    try {
      state.overview = await Api.getOverview(state.filter.cityIds[0]);
    } catch (e) {
      state.overview = null;
      setError(e.message);
    } finally {
      leaveBusy();
      renderOverview();
    }
  }

  /** 综合分析：一次拿到对比 + 分类两个 Tab 的全部数据 */
  async function loadAnalysis() {
    enterBusy();
    try {
      state.analysis = await Api.getAnalysis(params());
    } catch (e) {
      state.analysis = null;
      setError(e.message);
    } finally {
      leaveBusy();
      renderAnalysis();
      renderKpis();
      renderVerdict();
    }
  }

  function reloadAll() {
    setError('');
    return Promise.all([
      loadStats(), loadSeries(), loadRecords(), loadOverview(), loadAnalysis()
    ]);
  }

  // ----------------------------------------------------------------
  // 渲染：筛选区
  // ----------------------------------------------------------------

  function renderDataMax() {
    if (el.dataMax) el.dataMax.textContent = state.range.max || '—';
  }

  function renderCityChips() {
    if (!el.cityChips) return;
    var selected = selectedKeySet();

    if (state.cities.length === 0) {
      el.cityChips.innerHTML = '<span class="muted">加载中…</span>';
    } else {
      el.cityChips.innerHTML = state.cities.map(function (city) {
        var active = selected[String(city.code)] ? ' active' : '';
        return '<button type="button" class="chip' + active + '" data-code="' +
          esc(city.code) + '">' + esc(city.name) + '</button>';
      }).join('');
    }

    if (el.cityCount) {
      el.cityCount.textContent = '已选 ' + state.filter.cityIds.length + ' / ' + state.cities.length;
    }

    var emptySelection = state.filter.cityIds.length === 0;
    if (el.filterHint) {
      el.filterHint.textContent = emptySelection ? '请至少选择一个城市' : '';
      show(el.filterHint, emptySelection);
    }
    renderBusy();
  }

  function renderMetricChips() {
    if (!el.metricChips) return;
    el.metricChips.innerHTML = state.metrics.map(function (metric) {
      var active = state.filter.metric === metric.key ? ' active' : '';
      return '<button type="button" class="chip' + active + '" data-key="' +
        esc(metric.key) + '" data-label="' + esc(metric.label) + '" data-unit="' +
        esc(metric.unit) + '">' + esc(metric.label) + '</button>';
    }).join('');
  }

  function renderRangeInputs() {
    if (el.startDate) {
      el.startDate.min = state.range.min || '';
      el.startDate.max = state.filter.end || state.range.max || '';
      el.startDate.value = state.filter.start || '';
    }
    if (el.endDate) {
      el.endDate.min = state.filter.start || state.range.min || '';
      el.endDate.max = state.range.max || '';
      el.endDate.value = state.filter.end || '';
    }
    var days = '';
    if (state.filter.start && state.filter.end) {
      var a = new Date(state.filter.start + 'T00:00:00');
      var b = new Date(state.filter.end + 'T00:00:00');
      var span = Math.round((b - a) / 86400000) + 1;
      if (span > 0) days = '（' + span + ' 天）';
    }
    if (el.rangeText) {
      el.rangeText.textContent = (state.range.min || '—') + ' ~ ' + (state.range.max || '—') + days;
    }
  }

  // ----------------------------------------------------------------
  // 渲染：KPI 卡行
  // ----------------------------------------------------------------

  function renderVerdict() {
    if (!el.verdictValue) return;
    var a = state.analysis;
    var avgAqi = null;
    if (a && a.cities && a.cities.length) {
      var sum = 0, n = 0;
      a.cities.forEach(function (c) {
        var v = num(c.aqi);
        if (v !== null) { sum += v; n++; }
      });
      if (n) avgAqi = sum / n;
    } else if (state.stats && state.stats.length && state.filter.metric === 'aqi') {
      var s = 0, m = 0;
      state.stats.forEach(function (r) {
        var v = num(r.avg);
        if (v !== null) { s += v; m++; }
      });
      if (m) avgAqi = s / m;
    }
    if (avgAqi === null) {
      el.verdictValue.textContent = '—';
      return;
    }
    var meta = Api.getLevelMeta(Math.round(avgAqi));
    el.verdictValue.textContent = meta.label + ' · ' + avgAqi.toFixed(0);
    el.verdictValue.style.color = meta.color === '#FFFF00' ? '#fde047' : meta.color;
  }

  function renderKpis() {
    if (!el.kpiRow) return;
    var a = state.analysis;
    var cities = (a && a.cities) || [];
    var meta = Api.getMetricMeta(state.filter.metric);
    var unit = meta.unit ? '<span class="unit">' + esc(meta.unit) + '</span>' : '';

    // ---- 1) 综合指数：所选城市的均值 ----
    var ciAvg = null;
    if (cities.length) {
      var sum = 0, n = 0;
      cities.forEach(function (c) {
        var v = num(c.compositeIndex);
        if (v !== null) { sum += v; n++; }
      });
      if (n) ciAvg = sum / n;
    }
    var ciBand = bandOf(ciAvg);

    // ---- 2) 当前指标均值（沿用 A3） ----
    var statAvgs = (state.stats || [])
      .map(function (r) { return num(r.avg); })
      .filter(function (v) { return v !== null && !isNaN(v); });
    var mean = statAvgs.length
      ? (statAvgs.reduce(function (x, y) { return x + y; }, 0) / statAvgs.length).toFixed(1)
      : null;

    // ---- 3) 优良率 ----
    var goodRatio = null;
    if (cities.length) {
      var goodDays = cities.reduce(function (s, c) { return s + (Number(c.goodDays) || 0); }, 0);
      var samples = cities.reduce(function (s, c) { return s + (Number(c.samples) || 0); }, 0);
      if (samples) goodRatio = (goodDays * 100) / samples;
    }

    // ---- 5) 首要污染物：取天数最多的那一项（"无"要单独处理，它表示没有超标项） ----
    var top = null;
    if (a && a.pollutants && a.pollutants.length) {
      var ranked = a.pollutants.filter(function (p) { return p.name !== '无'; });
      top = ranked.length ? ranked[0] : a.pollutants[0];
    }

    // ---- 6) 最清洁 / 最污染（按综合指数） ----
    var cleanest = cities.length ? cities[0] : null;
    var dirtiest = cities.length ? cities[cities.length - 1] : null;

    var cards = [
      {
        cls: ciBand.tone,
        label: '综合指数（越小越清洁）',
        value: ciAvg === null ? '—' : ciAvg.toFixed(2),
        sub: ciBand.label === '—' ? '需完整六项浓度' : '整体' + ciBand.label
      },
      {
        cls: 'kpi-slate',
        label: meta.label + ' 均值',
        value: mean === null ? '—' : mean,
        unit: unit,
        sub: state.stats.length ? state.stats.length + ' 城平均' : '—'
      },
      {
        cls: 'kpi-teal',
        label: '优良天数占比',
        value: goodRatio === null ? '—' : goodRatio.toFixed(1) + '%',
        bar: goodRatio === null ? null : goodRatio
      },
      {
        cls: 'kpi-slate',
        label: '样本量',
        value: a ? String(a.samples) : '—',
        sub: cities.length + ' 城 · ' + (a ? a.samples === 0 ? 0 : Math.round(a.samples / Math.max(cities.length, 1)) : 0) + ' 天/城'
      },
      {
        cls: top && top.name === '无' ? 'kpi-good' : 'kpi-warn',
        label: '主要首要污染物',
        value: top ? top.name : '—',
        sub: top ? top.days + ' 天（' + fmt(top.ratio, 1) + '%）' : '—'
      },
      {
        cls: 'kpi-slate',
        label: '最清洁 ↔ 最污染',
        value: cleanest && dirtiest ? (cleanest.city + ' ↔ ' + dirtiest.city) : '—',
        sub: cleanest && dirtiest
          ? ('指数 ' + fmt(cleanest.compositeIndex, 2) + ' ↔ ' + fmt(dirtiest.compositeIndex, 2))
          : '—'
      }
    ];

    el.kpiRow.innerHTML = cards.map(function (c) {
      return '<div class="kpi ' + c.cls + '">' +
        '<span class="kpi-label">' + esc(c.label) + '</span>' +
        '<span class="kpi-value">' + esc(c.value) + (c.unit || '') + '</span>' +
        (c.bar !== null && c.bar !== undefined
          ? '<span class="kpi-bar"><i style="width:' + Math.max(0, Math.min(100, c.bar)) + '%"></i></span>' +
            '<span class="kpi-sub">优+良天数 / 总天数</span>'
          : '<span class="kpi-sub">' + esc(c.sub || '') + '</span>') +
        '</div>';
    }).join('');
  }

  // ----------------------------------------------------------------
  // 渲染：总览
  // ----------------------------------------------------------------

  function renderOverview() {
    if (!el.focusCard) return;

    if (state.filter.cityIds.length === 0) {
      show(el.focusCard, false);
      return;
    }
    show(el.focusCard, true);

    if (el.focusHint) {
      el.focusHint.textContent = (state.overview ? state.overview.cityName : '—') + ' · 最新一日快照';
    }

    if (!state.overview) {
      el.overviewCard.innerHTML = '<div class="placeholder">暂无数据</div>';
      return;
    }

    var meta = Api.getLevelMeta(state.overview.aqi);
    el.overviewCard.innerHTML =
      '<div class="overview-body" style="border-left-color:' + meta.color + '">' +
        '<div class="left">' +
          '<div class="city">' + esc(state.overview.cityName) + '</div>' +
          '<div class="date">' + esc(state.overview.monitorDate) + ' 监测日</div>' +
        '</div>' +
        '<div class="center">' +
          '<div class="aqi" style="color:' + meta.color + '">' + esc(state.overview.aqi) + '</div>' +
          '<div class="aqi-label">AQI</div>' +
        '</div>' +
        '<div class="right">' +
          '<span class="badge" style="background:' + meta.color + ';color:' + meta.textColor + '">' +
            esc(meta.label) + '</span>' +
          '<div class="metric"><span class="k">首要污染物</span><span class="v">' +
            esc(Api.formatPrimary(state.overview.primaryPollutant)) + '</span></div>' +
        '</div>' +
      '</div>';
  }

  function renderSeries() {
    var meta = Api.getMetricMeta(state.filter.metric);
    if (el.trendTitle) el.trendTitle.textContent = meta.label + ' 趋势';
    if (el.trendRange) {
      el.trendRange.textContent = (state.filter.start && state.filter.end)
        ? (state.filter.start + ' ~ ' + state.filter.end)
        : '全域';
    }

    var series = (state.series && state.series.series) || [];
    if (series.length === 0) {
      show(el.trendEmpty, true);
      if (el.trendChart) el.trendChart.style.display = 'none';
      return;
    }
    show(el.trendEmpty, false);
    if (el.trendChart) el.trendChart.style.display = '';
    Charts.renderTrend(state.series);
  }

  function renderStats() {
    var meta = Api.getMetricMeta(state.filter.metric);
    if (el.compareHint) el.compareHint.textContent = meta.label + ' 时段均值，柱色=该城众数等级';
    if (el.compareMetric) el.compareMetric.textContent = meta.label;
    if (el.thMetric) {
      el.thMetric.innerHTML = esc(meta.label) +
        (meta.unit ? '<span class="unit">' + esc(meta.unit) + '</span>' : '');
    }

    if (state.stats.length === 0) {
      show(el.compareEmpty, true);
      show(el.pieEmpty, true);
      if (el.compareChart) el.compareChart.style.display = 'none';
      if (el.pieChart) el.pieChart.style.display = 'none';
      return;
    }

    show(el.compareEmpty, false);
    show(el.pieEmpty, false);
    if (el.compareChart) el.compareChart.style.display = '';
    if (el.pieChart) el.pieChart.style.display = '';

    Charts.renderCompare(state.stats, state.filter.metric);
    Charts.renderPie(state.stats);
  }

  // ----------------------------------------------------------------
  // 渲染：对比 + 分类（均由 /analysis 驱动）
  // ----------------------------------------------------------------

  function renderAnalysis() {
    var a = state.analysis;
    var hasData = !!(a && a.cities && a.cities.length > 0);

    // --- 对比 Tab ---
    toggleChart(el.rankChart, el.rankEmpty, hasData);
    toggleChart(el.radarChart, el.radarEmpty, hasData);
    if (hasData) {
      Charts.renderRank(a.cities);
      Charts.renderRadar(a.cities.slice(0, 6));
      if (el.radarHint) {
        el.radarHint.textContent = a.cities.length > 6
          ? '取综合指数前 6 城 · 1.0 = 达标线'
          : '六项浓度 / 二级标准限值，1.0 = 达标线';
      }
    }
    renderProfileTable();

    // --- 分类 Tab ---
    var hasRegion = hasData && !!(a.regions && a.regions.length);
    var hasSeason = hasData && !!(a.seasons && a.seasons.length);
    var hasPollutant = hasData && !!(a.pollutants && a.pollutants.length);
    var hasLevels = hasData && !!(a.levels && a.levels.length);
    var hasHeat = hasData && !!(a.monthly && a.monthly.length);

    toggleChart(el.regionChart, el.regionEmpty, hasRegion);
    toggleChart(el.seasonChart, el.seasonEmpty, hasSeason);
    toggleChart(el.pollutantChart, el.pollutantEmpty, hasPollutant);
    toggleChart(el.levelBarChart, el.levelBarEmpty, hasLevels);
    toggleChart(el.heatmapChart, el.heatmapEmpty, hasHeat);

    if (hasRegion) Charts.renderRegion(a.regions);
    if (hasSeason) Charts.renderSeason(a.seasons);
    if (hasPollutant) Charts.renderPollutant(a.pollutants);
    if (hasLevels) {
      Charts.renderLevelBar(a.levels);
      if (el.levelHint) {
        var good = a.levels[0] && a.levels[1]
          ? (Number(a.levels[0].ratio || 0) + Number(a.levels[1].ratio || 0)).toFixed(1)
          : '—';
        el.levelHint.textContent = '国标 HJ 633-2012 · 优良合计 ' + good + '%';
      }
    }
    if (hasHeat) {
      // 热力图的城市顺序按综合指数排名，读图时"上面更干净"，与排名图一致
      var order = {};
      a.cities.forEach(function (c) { order[c.city] = c.rank; });
      var cells = a.monthly.slice().sort(function (x, y) {
        return (order[x.city] || 99) - (order[y.city] || 99);
      });
      Charts.renderHeatmap(cells);
    }

    // --- 总览的月度走势 ---
    var hasMonth = hasData && !!(a.monthlyTrend && a.monthlyTrend.length);
    toggleChart(el.monthChart, el.monthEmpty, hasMonth);
    if (hasMonth) Charts.renderMonth(a.monthlyTrend);
  }

  function toggleChart(chartNode, emptyNode, hasData) {
    if (emptyNode) show(emptyNode, !hasData);
    if (chartNode) chartNode.style.display = hasData ? '' : 'none';
  }

  /** 城市综合档案表：支持点表头排序 */
  function renderProfileTable() {
    if (!el.profileBody) return;
    var a = state.analysis;
    var rows = (a && a.cities) ? a.cities.slice() : [];

    if (rows.length === 0) {
      el.profileBody.innerHTML =
        '<tr><td colspan="9" class="placeholder">当前筛选条件下没有数据</td></tr>';
      return;
    }

    var sortKey = state.profileSort.key;
    var dir = state.profileSort.dir === 'asc' ? 1 : -1;
    rows.sort(function (x, y) {
      var xv = x[sortKey];
      var yv = y[sortKey];
      if (sortKey === 'city' || sortKey === 'region') {
        return String(xv).localeCompare(String(yv), 'zh-CN') * dir;
      }
      var xn = num(xv);
      var yn = num(yv);
      if (xn === null && yn === null) return 0;
      if (xn === null) return 1;
      if (yn === null) return -1;
      return (xn - yn) * dir;
    });

    el.profileBody.innerHTML = rows.map(function (c) {
      var ciBand = bandOf(c.compositeIndex);
      return '<tr data-code="' + esc(c.code) + '">' +
        '<td><span class="' + (medal ? 'rank-medal r' + c.rank : '') + '">' + esc(c.rank) + '</span></td>' +
        '<td><b>' + esc(c.city) + '</b></td>' +
        '<td>' + esc(c.region) + '</td>' +
        '<td class="num"><b>' + fmt(c.compositeIndex, 2) + '</b></td>' +
        '<td class="num">' + fmt(c.aqi, 1) + '</td>' +
        '<td class="num">' + fmt(c.pm25, 1) + '</td>' +
        '<td class="num">' + fmt(c.pm10, 1) + '</td>' +
        '<td class="num">' + fmt(c.o3, 1) + '</td>' +
        '<td class="num">' + fmt(c.goodRatio, 1) + '%' +
          '<span class="kpi-sub">' + ciBand.label + '</span></td>' +
        '</tr>';
    }).join('');

    // 表头排序箭头
    var ths = el.profileTable ? el.profileTable.querySelectorAll('th[data-sort]') : [];
    ths.forEach(function (th) {
      th.classList.remove('sorted-asc', 'sorted-desc');
      if (th.getAttribute('data-sort') === sortKey) {
        th.classList.add(state.profileSort.dir === 'asc' ? 'sorted-asc' : 'sorted-desc');
      }
    });
  }

  // ----------------------------------------------------------------
  // 渲染：明细（F8）
  // ----------------------------------------------------------------

  function renderTable() {
    if (!el.tableBody) return;
    var rows = state.pageData.list || [];
    var total = Number(state.pageData.total) || 0;
    var size = Number(state.pageData.size) || PAGE_SIZE;
    var totalPages = Math.max(1, Math.ceil(total / size));
    var page = Number(state.pageData.page) || 1;

    if (rows.length === 0) {
      el.tableBody.innerHTML =
        '<tr><td colspan="6" class="placeholder">当前筛选条件下没有数据</td></tr>';
    } else {
      el.tableBody.innerHTML = rows.map(function (row) {
        var level = Api.getLevelMetaByLevel(row.level);
        var bg = level ? level.color : '#9ca3af';
        var fg = level ? level.textColor : '#ffffff';
        var value = (row.value === null || row.value === undefined || row.value === '') ? '—' : row.value;
        return '<tr class="clickable" data-id="' + esc(row.id) + '">' +
          '<td>' + esc(row.city) + '</td>' +
          '<td>' + esc(row.date) + '</td>' +
          '<td class="num">' + esc(value) + '</td>' +
          '<td class="num">' + esc(row.aqi) + '</td>' +
          '<td><span class="badge" style="background:' + bg + ';color:' + fg + '">' +
            esc(row.levelLabel) + '</span></td>' +
          '<td>' + esc(Api.formatPrimary(row.primaryPollutant)) + '</td>' +
        '</tr>';
      }).join('');
    }

    if (el.pageInfo) {
      el.pageInfo.textContent = '第 ' + page + ' / ' + totalPages + ' 页 · 共 ' + total + ' 条';
    }
    if (el.btnPrev) el.btnPrev.disabled = page <= 1;
    if (el.btnNext) el.btnNext.disabled = page >= totalPages;
  }

  function renderDetail() {
    if (!el.detailCard) return;
    if (!state.detail) {
      show(el.detailCard, false);
      return;
    }
    show(el.detailCard, true);
    var d = state.detail;

    if (el.detailId) el.detailId.textContent = 'ID ' + esc(d.id);

    var items = [
      ['城市', esc(d.city) + '（' + esc(d.code) + '）'],
      ['日期', esc(d.date)],
      ['AQI', esc(d.aqi) + ' · ' + esc(d.levelLabel)],
      ['首要污染物', esc(d.primaryPollutant || '无')],
      ['PM2.5', Api.formatValue(d.pm25, 'μg/m³')],
      ['PM10', Api.formatValue(d.pm10, 'μg/m³')],
      ['SO₂', Api.formatValue(d.so2, 'μg/m³')],
      ['NO₂', Api.formatValue(d.no2, 'μg/m³')],
      ['CO', Api.formatValue(d.co, 'mg/m³')],
      ['O₃', Api.formatValue(d.o3, 'μg/m³')]
    ];

    el.detailGrid.innerHTML = items.map(function (pair) {
      return '<div><dt>' + pair[0] + '</dt><dd>' + pair[1] + '</dd></div>';
    }).join('');
  }

  // ----------------------------------------------------------------
  // Tab 切换
  // ----------------------------------------------------------------

  function switchTab(name) {
    state.tab = name;

    var tabs = el.tabs ? el.tabs.querySelectorAll('.tab') : [];
    tabs.forEach(function (tab) {
      tab.classList.toggle('is-active', tab.getAttribute('data-tab') === name);
    });

    var views = document.querySelectorAll('.view');
    views.forEach(function (view) {
      var match = view.getAttribute('data-view') === name;
      if (match) view.removeAttribute('hidden');
      else view.setAttribute('hidden', 'hidden');
    });

    // 隐藏期间容器宽度为 0，ECharts 会按 0 宽高渲染 —— 切回来必须 resize
    // 用 setTimeout 让浏览器先完成布局，否则仍可能读到 0
    setTimeout(function () { Charts.resizeAll(); }, 30);
  }

  // ----------------------------------------------------------------
  // 事件绑定
  // ----------------------------------------------------------------

  function bindEvents() {
    // 城市多选（事件委托：chips 是动态生成的）
    el.cityChips.addEventListener('click', function (event) {
      var chip = event.target.closest('[data-code]');
      if (!chip) return;
      var code = chip.getAttribute('data-code');
      var index = state.filter.cityIds.findIndex(function (item) {
        return String(item) === String(code);
      });
      if (index >= 0) {
        state.filter.cityIds.splice(index, 1);
      } else {
        state.filter.cityIds.push(code);
      }
      renderCityChips();
    });

    // 全选 / 清空
    document.querySelectorAll('[data-act]').forEach(function (button) {
      button.addEventListener('click', function () {
        var act = button.getAttribute('data-act');
        if (act === 'city-all') {
          state.filter.cityIds = state.cities.map(function (city) { return city.code; });
        } else if (act === 'city-none') {
          state.filter.cityIds = [];
        }
        renderCityChips();
      });
    });

    // 指标切换
    el.metricChips.addEventListener('click', function (event) {
      var chip = event.target.closest('[data-key]');
      if (!chip) return;
      var next = chip.getAttribute('data-key');
      if (next === state.filter.metric) return;
      state.filter.metric = next;
      renderMetricChips();

      // 指标切换后必须立即重载，不能等"查询"。
      // 否则会停留在"高亮 PM2.5、表头仍写 AQI、数字仍是 AQI 均值"的自相矛盾状态。
      // （城市与日期仍沿用点"查询"才加载：前者是多选、后者可能输入到一半。）
      state.page = 1;
      reloadAll();
    });

    // 日期区间
    el.startDate.addEventListener('change', function () {
      state.filter.start = el.startDate.value;
      renderRangeInputs();
    });
    el.endDate.addEventListener('change', function () {
      state.filter.end = el.endDate.value;
      renderRangeInputs();
    });

    // 快捷区间：以数据集末日为终点，向前推 days-1 天（含端点）
    document.querySelectorAll('[data-days]').forEach(function (button) {
      button.addEventListener('click', function () {
        var days = Number(button.getAttribute('data-days'));
        if (!state.range.max) return;
        var cursor = new Date(state.range.max + 'T00:00:00');
        cursor.setDate(cursor.getDate() - (days - 1));
        var start = toISODate(cursor);
        state.filter.start = (state.range.min && start < state.range.min) ? state.range.min : start;
        state.filter.end = state.range.max;
        renderRangeInputs();
      });
    });

    // 查询
    el.btnSearch.addEventListener('click', function () {
      if (state.filter.cityIds.length === 0) return;
      state.page = 1;
      reloadAll();
    });

    // 刷新
    el.btnRefresh.addEventListener('click', function () {
      reloadAll();
    });

    // Tab
    if (el.tabs) {
      el.tabs.addEventListener('click', function (event) {
        var tab = event.target.closest('[data-tab]');
        if (!tab) return;
        switchTab(tab.getAttribute('data-tab'));
      });
    }

    // 综合档案表排序
    if (el.profileTable) {
      el.profileTable.addEventListener('click', function (event) {
        var th = event.target.closest('th[data-sort]');
        if (!th) return;
        var key = th.getAttribute('data-sort');
        if (state.profileSort.key === key) {
          state.profileSort.dir = state.profileSort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          state.profileSort.key = key;
          state.profileSort.dir = (key === 'city' || key === 'region') ? 'asc' : 'asc';
        }
        renderProfileTable();
      });
    }

    // 翻页
    el.btnPrev.addEventListener('click', function () {
      if (state.page <= 1) return;
      state.page--;
      loadRecords();
    });
    el.btnNext.addEventListener('click', function () {
      state.page++;
      loadRecords();
    });

    // 行点击 -> A4 单条详情
    el.tableBody.addEventListener('click', async function (event) {
      var row = event.target.closest('[data-id]');
      if (!row) return;
      var id = row.getAttribute('data-id');
      try {
        state.detail = await Api.getRecord(id);
      } catch (e) {
        state.detail = null;
        setError(e.message);
      }
      renderDetail();
    });

    el.btnCloseDetail.addEventListener('click', function () {
      state.detail = null;
      renderDetail();
    });
  }

  // ----------------------------------------------------------------
  // 启动
  // ----------------------------------------------------------------

  async function main() {
    cacheDom();
    bindEvents();

    renderCityChips();
    renderMetricChips();
    renderKpis();
    renderTable();
    renderProfileTable();
    renderBusy();

    await loadBase();
    await reloadAll();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', main);
  } else {
    main();
  }
})(window);
