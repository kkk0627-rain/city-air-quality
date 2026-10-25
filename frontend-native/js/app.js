/**
 * 原生版看板主控：状态编排 + DOM 渲染。
 *
 * 设计取舍：
 *  - 没有虚拟 DOM，因此采用「集中 state + 显式 render」的模式：
 *    所有请求只改 state，改完调用对应的 render*()，避免 DOM 与数据脱节；
 *  - 事件一律用委托绑在容器上（城市/指标是动态生成的），容器本身静态存在于 HTML；
 *  - 每个请求各自维护 busy 计数，用于按钮禁用，避免并发时按钮闪烁。
 */
(function (global) {
  'use strict';

  var Api = global.Api;
  var Charts = global.Charts;

  /** F8 需求指定：每页 10 条 */
  var PAGE_SIZE = 10;

  // ----------------------------------------------------------------
  // 状态
  // ----------------------------------------------------------------

  var state = {
    cities: [],
    metrics: Api.METRICS.slice(),
    range: { min: '', max: '' },
    filter: { cityIds: [], metric: 'aqi', start: '', end: '' },
    page: 1,
    stats: [],
    series: { dates: [], series: [] },
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
    'dataMax', 'btnRefresh', 'errorBar', 'cityCount', 'cityChips', 'metricChips',
    'rangeText', 'startDate', 'endDate', 'btnSearch', 'filterHint', 'kpiRow',
    'focusCard', 'focusHint', 'overviewCard', 'trendTitle', 'trendRange',
    'trendChart', 'trendEmpty', 'compareHint', 'compareChart', 'compareEmpty',
    'pieChart', 'pieEmpty', 'thMetric', 'tableBody', 'btnPrev', 'btnNext',
    'pageInfo', 'detailCard', 'detailId', 'detailGrid', 'btnCloseDetail'
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

  function reloadAll() {
    setError('');
    return Promise.all([loadStats(), loadSeries(), loadRecords(), loadOverview()]);
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
      el.cityChips.innerHTML = '<span class="empty">加载中…</span>';
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
    if (el.rangeText) {
      el.rangeText.textContent = (state.range.min || '—') + ' ~ ' + (state.range.max || '—');
    }
  }

  // ----------------------------------------------------------------
  // 渲染：主区域
  // ----------------------------------------------------------------

  function renderKpis() {
    if (!el.kpiRow) return;
    var rows = state.stats || [];
    var meta = Api.getMetricMeta(state.filter.metric);
    var unit = meta.unit ? ' ' + meta.unit : '';

    if (rows.length === 0) {
      el.kpiRow.innerHTML = [
        { label: '覆盖城市', value: '—' },
        { label: meta.label + ' 均值', value: '—' },
        { label: '优良天数占比', value: '—' },
        { label: '污染最重城市', value: '—' }
      ].map(kpiHtml).join('');
      return;
    }

    var avgs = rows
      .map(function (row) {
        return (row.avg === null || row.avg === undefined) ? null : Number(row.avg);
      })
      .filter(function (value) { return value !== null && !isNaN(value); });

    var mean = avgs.length
      ? (avgs.reduce(function (a, b) { return a + b; }, 0) / avgs.length).toFixed(1) + unit
      : '—';

    var good = rows.reduce(function (sum, row) { return sum + (Number(row.goodDays) || 0); }, 0);
    var samples = rows.reduce(function (sum, row) { return sum + (Number(row.samples) || 0); }, 0);
    var ratio = samples > 0 ? ((good * 100) / samples).toFixed(1) + '%' : '—';

    var worst = rows
      .filter(function (row) { return row.avg !== null && row.avg !== undefined; })
      .slice()
      .sort(function (a, b) { return Number(b.avg) - Number(a.avg); })[0];

    el.kpiRow.innerHTML = [
      { label: '覆盖城市', value: rows.length + ' 个' },
      { label: meta.label + ' 均值', value: mean },
      { label: '优良天数占比', value: ratio },
      { label: '污染最重城市', value: worst ? (worst.city + ' ' + worst.avg) : '—' }
    ].map(kpiHtml).join('');
  }

  function kpiHtml(item) {
    return '<div class="kpi"><span class="kpi-label">' + esc(item.label) +
      '</span><span class="kpi-value">' + esc(item.value) + '</span></div>';
  }

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
    if (el.compareHint) el.compareHint.textContent = meta.label + ' 时段均值';
    if (el.thMetric) {
      el.thMetric.innerHTML = esc(meta.label) +
        (meta.unit ? '<span class="unit">' + esc(meta.unit) + '</span>' : '');
    }

    renderKpis();

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

  function renderTable() {
    if (!el.tableBody) return;
    var rows = state.pageData.list || [];
    var total = Number(state.pageData.total) || 0;
    var size = Number(state.pageData.size) || PAGE_SIZE;
    var totalPages = Math.max(1, Math.ceil(total / size));
    var page = Number(state.pageData.page) || 1;

    if (rows.length === 0) {
      el.tableBody.innerHTML = '<tr><td colspan="5" class="placeholder">当前筛选条件下没有数据</td></tr>';
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
