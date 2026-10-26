/**
 * 原生版图表层：ECharts 实例管理 + option 构造。
 *
 * ECharts 从 vendor/echarts.min.js 以全局变量方式引入（未走 npm/CDN，
 * 保证离线可用）。本文件只做两件事：
 *  1. 管理图表实例的创建/渲染/销毁/自适应；
 *  2. 把后端返回的数据翻译成 ECharts option。
 * 业务状态一律由 app.js 持有，这里不保存任何筛选条件。
 *
 * 十张图分三组：
 *  总览 —— 趋势折线(F5) / 等级环形(F7) / 月度走势
 *  对比 —— 综合指数排名 / 污染物雷达 / 城市对比柱状(F6)
 *  分类 —— 区域 / 季节 / 首要污染物 / 等级天数 / 城市×月份热力图
 */
(function (global) {
  'use strict';

  var echarts = global.echarts;

  /** 12 城轮换调色板：相邻色相拉开，避免多条折线难以区分 */
  var LINE_COLORS = [
    '#2563eb', '#dc2626', '#059669', '#d97706',
    '#7c3aed', '#0891b2', '#db2777', '#65a30d',
    '#ea580c', '#4f46e5', '#0d9488', '#9333ea'
  ];

  var AXIS_TEXT = '#64748b';
  var AXIS_LINE = '#d5dde8';
  var SPLIT_LINE = '#eef2f7';

  /**
   * 创建一个受管图表实例。
   * 首次 show() 时才 init —— 容器若在 display:none 的父节点里，
   * 提前 init 会取到 0 宽高，导致图表画不出来。
   */
  function create(id) {
    var chart = null;

    return {
      show: function (option) {
        var el = document.getElementById(id);
        if (!el) return;
        // 从隐藏 Tab 切回来时容器重新有尺寸，必须在 setOption 后再 resize 一次
        if (!chart) chart = echarts.init(el);
        chart.setOption(option, true);
        chart.resize();
      },
      clear: function () {
        if (chart) chart.clear();
      },
      resize: function () {
        if (chart) chart.resize();
      },
      dispose: function () {
        if (chart) {
          chart.dispose();
          chart = null;
        }
      }
    };
  }

  var instances = {
    trend: create('trendChart'),
    pie: create('pieChart'),
    month: create('monthChart'),
    rank: create('rankChart'),
    radar: create('radarChart'),
    compare: create('compareChart'),
    region: create('regionChart'),
    season: create('seasonChart'),
    pollutant: create('pollutantChart'),
    levelBar: create('levelBarChart'),
    heatmap: create('heatmapChart')
  };

  // ----------------------------------------------------------------
  // 公共片段
  // ----------------------------------------------------------------

  function baseGrid(top) {
    return { left: 8, right: 18, top: top || 24, bottom: 8, containLabel: true };
  }

  function categoryAxis(data, rotate) {
    return {
      type: 'category',
      data: data,
      axisLabel: { fontSize: 12, color: AXIS_TEXT, interval: 0, rotate: rotate || 0 },
      axisLine: { lineStyle: { color: AXIS_LINE } },
      axisTick: { show: false }
    };
  }

  function valueAxis(name) {
    return {
      type: 'value',
      name: name || '',
      nameTextStyle: { fontSize: 11, color: '#94a3b8' },
      axisLabel: { fontSize: 12, color: AXIS_TEXT },
      splitLine: { lineStyle: { color: SPLIT_LINE } }
    };
  }

  function tooltipAxis() {
    return {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#94a3b8' } }
    };
  }

  function tooltipItem() {
    return {
      trigger: 'item',
      backgroundColor: 'rgba(15,23,42,.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 }
    };
  }

  // ----------------------------------------------------------------
  // 总览：趋势折线（F5）
  // ----------------------------------------------------------------

  function buildTrend(data) {
    var dates = (data && data.dates) || [];
    var series = (data && data.series) || [];
    var meta = global.Api.getMetricMeta(data && data.metric ? data.metric : 'aqi');

    return {
      tooltip: tooltipAxis(),
      legend: {
        top: 0,
        type: 'scroll',
        itemWidth: 14,
        itemHeight: 8,
        textStyle: { fontSize: 12, color: AXIS_TEXT }
      },
      grid: { left: 8, right: 20, top: 44, bottom: 8, containLabel: true },
      xAxis: {
        type: 'category',
        data: dates,
        boundaryGap: false,
        axisLabel: { fontSize: 12, color: AXIS_TEXT },
        axisLine: { lineStyle: { color: AXIS_LINE } },
        axisTick: { show: false }
      },
      yAxis: valueAxis(meta.unit),
      series: series.map(function (item, index) {
        var color = LINE_COLORS[index % LINE_COLORS.length];
        return {
          name: item.city,
          type: 'line',
          smooth: true,
          showSymbol: false,
          // 缺测日期为 null，此处必须断线，否则会把空洞连成虚假趋势
          connectNulls: false,
          lineStyle: { width: 2, color: color },
          itemStyle: { color: color },
          data: (item.values || []).map(function (value) {
            return value === null || value === undefined ? null : Number(value);
          })
        };
      })
    };
  }

  // ----------------------------------------------------------------
  // 总览：等级分布环形（F7）
  // ----------------------------------------------------------------

  function buildPie(stats) {
    var distribution = global.Api.AQI_LEVELS.map(function (meta) {
      var days = (stats || []).reduce(function (sum, row) {
        var counts = row.levelCounts || {};
        return sum + (Number(counts[meta.level]) || 0);
      }, 0);
      return { label: meta.label, color: meta.color, days: days };
    });

    var data = distribution
      .filter(function (item) { return item.days > 0; })
      .map(function (item) {
        return { name: item.label, value: item.days, itemStyle: { color: item.color } };
      });

    return {
      tooltip: Object.assign(tooltipItem(), {
        formatter: function (p) { return p.name + '<br/>' + p.value + ' 天（' + p.percent + '%）'; }
      }),
      legend: {
        bottom: 0,
        itemWidth: 10,
        itemHeight: 10,
        textStyle: { fontSize: 12, color: AXIS_TEXT }
      },
      series: [{
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 13, fontWeight: 500, formatter: '{b}\n{c} 天' }
        },
        data: data
      }]
    };
  }

  // ----------------------------------------------------------------
  // 总览：月度走势（看季节性）
  // ----------------------------------------------------------------

  function buildMonth(points) {
    var list = points || [];
    return {
      tooltip: tooltipAxis(),
      grid: baseGrid(20),
      xAxis: categoryAxis(list.map(function (p) { return p.month; })),
      yAxis: valueAxis('AQI'),
      series: [{
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 7,
        lineStyle: { width: 2.5, color: '#0ea5e9' },
        itemStyle: { color: '#0ea5e9' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(14,165,233,.28)' },
              { offset: 1, color: 'rgba(14,165,233,0)' }
            ]
          }
        },
        data: list.map(function (p) { return Number(p.aqi); })
      }]
    };
  }

  // ----------------------------------------------------------------
  // 对比：综合指数排名（横向条，越小越清洁）
  // ----------------------------------------------------------------

  function buildRank(cities) {
    // 图上从下往上画，所以要反转：最清洁的排在最上面
    var list = (cities || []).slice().reverse();

    function colorOf(ci) {
      if (ci === null || ci === undefined) return '#94a3b8';
      var v = Number(ci);
      if (v < 2.2) return '#16a34a';
      if (v < 2.8) return '#65a30d';
      if (v < 3.4) return '#d97706';
      if (v < 4.0) return '#dc2626';
      return '#991b1b';
    }

    return {
      tooltip: Object.assign(tooltipAxis(), {
        axisPointer: { type: 'shadow' },
        formatter: function (params) {
          var p = params[0];
          var row = list[p.dataIndex];
          return row.city + '<br/>综合指数 <b>' + row.compositeIndex + '</b>（越小越清洁）' +
            '<br/>AQI 均值 ' + row.aqi + ' · 优良率 ' + row.goodRatio + '%' +
            '<br/>第 ' + row.rank + ' 名 · ' + row.region;
        }
      }),
      grid: { left: 8, right: 60, top: 12, bottom: 8, containLabel: true },
      xAxis: {
        type: 'value',
        name: '综合指数',
        nameTextStyle: { fontSize: 11, color: '#94a3b8' },
        axisLabel: { fontSize: 12, color: AXIS_TEXT },
        splitLine: { lineStyle: { color: SPLIT_LINE } }
      },
      yAxis: categoryAxis(list.map(function (c) { return c.city; })),
      series: [{
        type: 'bar',
        barMaxWidth: 16,
        data: list.map(function (c) {
          return {
            value: c.compositeIndex === null ? 0 : Number(c.compositeIndex),
            itemStyle: { color: colorOf(c.compositeIndex), borderRadius: [0, 4, 4, 0] }
          };
        }),
        label: {
          show: true,
          position: 'right',
          fontSize: 12,
          color: AXIS_TEXT,
          formatter: function (p) {
            return list[p.dataIndex].compositeIndex === null ? '—' : p.value.toFixed(2);
          }
        }
      }]
    };
  }

  // ----------------------------------------------------------------
  // 对比：污染物雷达（六项单项质量指数，1.0 = 达标线）
  // ----------------------------------------------------------------

  var RADAR_FACTORS = [
    { key: 'pm25', label: 'PM2.5' },
    { key: 'pm10', label: 'PM10' },
    { key: 'so2', label: 'SO₂' },
    { key: 'no2', label: 'NO₂' },
    { key: 'co', label: 'CO' },
    { key: 'o3', label: 'O₃' }
  ];

  function buildRadar(cities) {
    var list = (cities || []).slice(0, 6);

    // 上限取所有参与城市的最大单项指数，再留 20% 余量，
    // 否则数值小的城市会被压成一条贴着圆心的线，看不出形状差异
    var peak = 1.2;
    list.forEach(function (c) {
      RADAR_FACTORS.forEach(function (f) {
        var v = c.ratios ? Number(c.ratios[f.key]) : NaN;
        if (!isNaN(v) && v > peak) peak = v;
      });
    });
    peak = Math.ceil(peak * 1.2 * 10) / 10;

    return {
      tooltip: tooltipItem(),
      legend: {
        bottom: 0,
        itemWidth: 12,
        itemHeight: 8,
        textStyle: { fontSize: 12, color: AXIS_TEXT }
      },
      radar: {
        center: ['50%', '46%'],
        radius: '64%',
        indicator: RADAR_FACTORS.map(function (f) {
          return { name: f.label, max: peak };
        }),
        axisName: { fontSize: 12, color: AXIS_TEXT },
        splitLine: { lineStyle: { color: SPLIT_LINE } },
        splitArea: { areaStyle: { color: ['#ffffff', '#f8fafc'] } },
        axisLine: { lineStyle: { color: AXIS_LINE } }
      },
      series: [{
        type: 'radar',
        symbolSize: 4,
        data: list.map(function (c, index) {
          var color = LINE_COLORS[index % LINE_COLORS.length];
          return {
            name: c.city,
            value: RADAR_FACTORS.map(function (f) {
              var v = c.ratios ? Number(c.ratios[f.key]) : NaN;
              return isNaN(v) ? 0 : v;
            }),
            lineStyle: { width: 2, color: color },
            itemStyle: { color: color },
            areaStyle: { color: color, opacity: .08 }
          };
        })
      }]
    };
  }

  // ----------------------------------------------------------------
  // 对比：城市对比柱状（F6）
  // ----------------------------------------------------------------

  function buildCompare(stats, metric) {
    var meta = global.Api.getMetricMeta(metric);
    var unit = meta.unit ? ' ' + meta.unit : '';

    var sorted = (stats || []).slice().sort(function (a, b) {
      var av = (a.avg === null || a.avg === undefined) ? Number.MAX_VALUE : Number(a.avg);
      var bv = (b.avg === null || b.avg === undefined) ? Number.MAX_VALUE : Number(b.avg);
      return av - bv;
    });

    /**
     * 柱子配色取该城市在时段内的「众数等级」（levelCounts 最大的一档），
     * 而不是按均值反推等级——后者对 PM2.5 这类浓度值没有意义
     * （等级分段是 AQI 标度，不是 μg/m³）。
     */
    function dominantLevel(row) {
      var counts = row.levelCounts || {};
      var best = 1;
      var bestCount = -1;
      Object.keys(counts).forEach(function (level) {
        var value = Number(counts[level]) || 0;
        if (value > bestCount) {
          bestCount = value;
          best = Number(level);
        }
      });
      return best;
    }

    var palette = {};
    global.Api.AQI_LEVELS.forEach(function (item) {
      palette[item.level] = item.color;
    });

    return {
      tooltip: Object.assign(tooltipAxis(), {
        axisPointer: { type: 'shadow' },
        formatter: function (params) {
          var p = params[0];
          var row = sorted[p.dataIndex];
          return row.city + '<br/>均值 ' + p.value + unit +
            '<br/>最大 ' + (row.max === null || row.max === undefined ? '—' : row.max) +
            ' · 最小 ' + (row.min === null || row.min === undefined ? '—' : row.min) +
            '<br/>优良率 ' + (row.goodRatio === null || row.goodRatio === undefined ? '—' : row.goodRatio) + '%';
        }
      }),
      grid: baseGrid(20),
      xAxis: categoryAxis(sorted.map(function (row) { return row.city; }), 30),
      yAxis: valueAxis(meta.unit),
      series: [{
        type: 'bar',
        barMaxWidth: 34,
        data: sorted.map(function (row) {
          return {
            value: (row.avg === null || row.avg === undefined) ? 0 : Number(row.avg),
            itemStyle: {
              color: palette[dominantLevel(row)] || '#9ca3af',
              borderRadius: [4, 4, 0, 0]
            }
          };
        })
      }]
    };
  }

  // ----------------------------------------------------------------
  // 分类：区域（柱=AQI 均值，折线=优良率）
  // ----------------------------------------------------------------

  function buildRegion(groups) {
    var list = groups || [];
    return {
      tooltip: tooltipAxis(),
      legend: {
        top: 0,
        itemWidth: 14,
        itemHeight: 8,
        textStyle: { fontSize: 12, color: AXIS_TEXT }
      },
      grid: baseGrid(40),
      xAxis: categoryAxis(list.map(function (g) { return g.name; })),
      yAxis: [
        valueAxis('AQI'),
        {
          type: 'value',
          name: '优良率%',
          nameTextStyle: { fontSize: 11, color: '#94a3b8' },
          axisLabel: { fontSize: 12, color: AXIS_TEXT, formatter: '{value}%' },
          splitLine: { show: false },
          max: 100
        }
      ],
      series: [
        {
          name: 'AQI 均值',
          type: 'bar',
          barMaxWidth: 32,
          itemStyle: { color: '#2563eb', borderRadius: [4, 4, 0, 0] },
          data: list.map(function (g) { return Number(g.aqi); })
        },
        {
          name: '优良率',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2.5, color: '#16a34a' },
          itemStyle: { color: '#16a34a' },
          data: list.map(function (g) { return Number(g.goodRatio); })
        },
        {
          name: '综合指数',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2, type: 'dashed', color: '#d97706' },
          itemStyle: { color: '#d97706' },
          // 综合指数是 2~4 的量级，画在 AQI 轴上会被压平，这里 ×20 只为同框对比
          data: list.map(function (g) {
            return g.compositeIndex === null ? null : Number(g.compositeIndex) * 20;
          }),
          tooltip: { valueFormatter: function (v) { return (v / 20).toFixed(2); } }
        }
      ]
    };
  }

  // ----------------------------------------------------------------
  // 分类：季节（柱 + 优良率折线）
  // ----------------------------------------------------------------

  function buildSeason(groups) {
    var list = groups || [];
    var colorMap = { '春': '#4ade80', '夏': '#38bdf8', '秋': '#fbbf24', '冬': '#94a3b8' };

    return {
      tooltip: tooltipAxis(),
      legend: { top: 0, itemWidth: 14, itemHeight: 8, textStyle: { fontSize: 12, color: AXIS_TEXT } },
      grid: baseGrid(40),
      xAxis: categoryAxis(list.map(function (g) { return g.name; })),
      yAxis: [
        valueAxis('AQI'),
        {
          type: 'value',
          name: '优良率%',
          nameTextStyle: { fontSize: 11, color: '#94a3b8' },
          axisLabel: { fontSize: 12, color: AXIS_TEXT, formatter: '{value}%' },
          splitLine: { show: false },
          max: 100
        }
      ],
      series: [
        {
          name: 'AQI 均值',
          type: 'bar',
          barMaxWidth: 46,
          data: list.map(function (g) {
            return {
              value: Number(g.aqi),
              itemStyle: { color: colorMap[g.name] || '#2563eb', borderRadius: [4, 4, 0, 0] }
            };
          })
        },
        {
          name: '优良率',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2.5, color: '#16a34a' },
          itemStyle: { color: '#16a34a' },
          data: list.map(function (g) { return Number(g.goodRatio); })
        }
      ]
    };
  }

  // ----------------------------------------------------------------
  // 分类：首要污染物（横向条）
  // ----------------------------------------------------------------

  var POLLUTANT_COLORS = {
    '无': '#16a34a',
    'PM2.5': '#dc2626',
    'PM10': '#d97706',
    'O3': '#0ea5e9',
    'SO2': '#7c3aed',
    'NO2': '#0891b2',
    'CO': '#64748b'
  };

  function buildPollutant(shares) {
    var list = (shares || []).slice().reverse();
    return {
      tooltip: Object.assign(tooltipAxis(), {
        axisPointer: { type: 'shadow' },
        formatter: function (params) {
          var p = params[0];
          return p.name + ' 作为首要污染物<br/>' + p.value + ' 天';
        }
      }),
      grid: { left: 8, right: 58, top: 12, bottom: 8, containLabel: true },
      xAxis: {
        type: 'value',
        name: '天数',
        nameTextStyle: { fontSize: 11, color: '#94a3b8' },
        axisLabel: { fontSize: 12, color: AXIS_TEXT },
        splitLine: { lineStyle: { color: SPLIT_LINE } }
      },
      yAxis: categoryAxis(list.map(function (s) { return s.name; })),
      series: [{
        type: 'bar',
        barMaxWidth: 18,
        data: list.map(function (s) {
          return {
            value: Number(s.days),
            itemStyle: {
              color: POLLUTANT_COLORS[s.name] || '#94a3b8',
              borderRadius: [0, 4, 4, 0]
            }
          };
        }),
        label: {
          show: true,
          position: 'right',
          fontSize: 12,
          color: AXIS_TEXT,
          formatter: function (p) {
            var s = list[p.dataIndex];
            return s.ratio === null ? p.value : p.value + '（' + s.ratio + '%）';
          }
        }
      }]
    };
  }

  // ----------------------------------------------------------------
  // 分类：六档等级天数（横向条，用国标配色）
  // ----------------------------------------------------------------

  function buildLevelBar(shares) {
    var list = (shares || []).slice().reverse();
    var colorOf = {};
    global.Api.AQI_LEVELS.forEach(function (item) { colorOf[item.label] = item.color; });

    return {
      tooltip: Object.assign(tooltipAxis(), {
        axisPointer: { type: 'shadow' },
        formatter: function (params) {
          var p = params[0];
          return p.name + '<br/>' + p.value + ' 天';
        }
      }),
      grid: { left: 8, right: 58, top: 12, bottom: 8, containLabel: true },
      xAxis: {
        type: 'value',
        name: '天数',
        nameTextStyle: { fontSize: 11, color: '#94a3b8' },
        axisLabel: { fontSize: 12, color: AXIS_TEXT },
        splitLine: { lineStyle: { color: SPLIT_LINE } }
      },
      yAxis: categoryAxis(list.map(function (s) { return s.name; })),
      series: [{
        type: 'bar',
        barMaxWidth: 18,
        data: list.map(function (s) {
          return {
            value: Number(s.days),
            itemStyle: { color: colorOf[s.name] || '#94a3b8', borderRadius: [0, 4, 4, 0] }
          };
        }),
        label: {
          show: true,
          position: 'right',
          fontSize: 12,
          color: AXIS_TEXT,
          formatter: function (p) {
            var s = list[p.dataIndex];
            return s.ratio === null ? p.value : p.value + '（' + s.ratio + '%）';
          }
        }
      }]
    };
  }

  // ----------------------------------------------------------------
  // 分类：城市 × 月份 热力图
  // ----------------------------------------------------------------

  function buildHeatmap(cells) {
    var list = cells || [];

    var months = [];
    var cityNames = [];
    list.forEach(function (c) {
      if (months.indexOf(c.month) < 0) months.push(c.month);
      if (cityNames.indexOf(c.city) < 0) cityNames.push(c.city);
    });
    months.sort();
    // 城市顺序沿用后端排名顺序（由调用方保证按 rank 传入），不按名称重排
    var data = list.map(function (c) {
      return [months.indexOf(c.month), cityNames.indexOf(c.city), Number(c.aqi)];
    });

    return {
      tooltip: {
        position: 'top',
        backgroundColor: 'rgba(15,23,42,.92)',
        borderWidth: 0,
        textStyle: { color: '#fff', fontSize: 12 },
        formatter: function (p) {
          return cityNames[p.value[1]] + ' · ' + months[p.value[0]] + '<br/>AQI 均值 ' + p.value[2];
        }
      },
      grid: { left: 8, right: 16, top: 8, bottom: 76, containLabel: true },
      xAxis: {
        type: 'category',
        data: months,
        splitArea: { show: true },
        axisLabel: { fontSize: 11, color: AXIS_TEXT, rotate: 45 },
        axisLine: { lineStyle: { color: AXIS_LINE } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'category',
        data: cityNames,
        splitArea: { show: true },
        axisLabel: { fontSize: 11, color: AXIS_TEXT },
        axisLine: { lineStyle: { color: AXIS_LINE } },
        axisTick: { show: false }
      },
      visualMap: {
        min: 20,
        max: 140,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: 8,
        itemWidth: 12,
        itemHeight: 140,
        textStyle: { fontSize: 11, color: AXIS_TEXT },
        // 由绿到红，与六档等级配色同向（绿=优，红=污染）
        inRange: { color: ['#4ade80', '#fef08a', '#fb923c', '#ef4444', '#7f1d1d'] }
      },
      series: [{
        type: 'heatmap',
        data: data,
        progressive: 0,
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        emphasis: { itemStyle: { borderColor: '#0f172a', borderWidth: 2 } }
      }]
    };
  }

  // ----------------------------------------------------------------
  // 对外接口
  // ----------------------------------------------------------------

  function renderTrend(data) { instances.trend.show(buildTrend(data)); }
  function renderPie(stats) { instances.pie.show(buildPie(stats)); }
  function renderMonth(points) { instances.month.show(buildMonth(points)); }
  function renderRank(cities) { instances.rank.show(buildRank(cities)); }
  function renderRadar(cities) { instances.radar.show(buildRadar(cities)); }
  function renderCompare(stats, metric) { instances.compare.show(buildCompare(stats, metric)); }
  function renderRegion(groups) { instances.region.show(buildRegion(groups)); }
  function renderSeason(groups) { instances.season.show(buildSeason(groups)); }
  function renderPollutant(shares) { instances.pollutant.show(buildPollutant(shares)); }
  function renderLevelBar(shares) { instances.levelBar.show(buildLevelBar(shares)); }
  function renderHeatmap(cells) { instances.heatmap.show(buildHeatmap(cells)); }

  // 窗口尺寸变化时统一 resize；容器宽度变化用 ResizeObserver 兜底
  var resizeTimer = null;
  global.addEventListener('resize', function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      Object.keys(instances).forEach(function (key) { instances[key].resize(); });
    }, 120);
  });

  global.Charts = {
    renderTrend: renderTrend,
    renderPie: renderPie,
    renderMonth: renderMonth,
    renderRank: renderRank,
    renderRadar: renderRadar,
    renderCompare: renderCompare,
    renderRegion: renderRegion,
    renderSeason: renderSeason,
    renderPollutant: renderPollutant,
    renderLevelBar: renderLevelBar,
    renderHeatmap: renderHeatmap,
    resizeAll: function () {
      Object.keys(instances).forEach(function (key) { instances[key].resize(); });
    },
    instances: instances
  };
})(window);
