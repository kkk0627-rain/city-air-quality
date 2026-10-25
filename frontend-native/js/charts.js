/**
 * 原生版图表层：ECharts 实例管理 + option 构造。
 *
 * ECharts 从 vendor/echarts.min.js 以全局变量方式引入（未走 npm/CDN，
 * 保证离线可用）。本文件只做两件事：
 *  1. 管理三个图表实例的创建/渲染/销毁/自适应；
 *  2. 把 A3/A2 的返回数据翻译成 ECharts option。
 * 业务状态一律由 app.js 持有，这里不保存任何筛选条件。
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
    compare: create('compareChart'),
    pie: create('pieChart')
  };

  // ----------------------------------------------------------------
  // option 构造
  // ----------------------------------------------------------------

  /**
   * 多城市趋势折线图（F5）。
   * @param {{ metric:string, dates:string[], series:{code,city,values:number[]}[] }} data
   */
  function buildTrend(data) {
    var dates = (data && data.dates) || [];
    var series = (data && data.series) || [];
    var meta = global.Api.getMetricMeta(data && data.metric ? data.metric : 'aqi');

    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
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
        data: dates,
        boundaryGap: false,
        axisLabel: { fontSize: 12, color: '#4b5563' },
        axisLine: { lineStyle: { color: '#d1d5db' } }
      },
      yAxis: {
        type: 'value',
        name: meta.unit,
        nameTextStyle: { fontSize: 11, color: '#6b7280' },
        axisLabel: { fontSize: 12, color: '#4b5563' },
        splitLine: { lineStyle: { color: '#f1f0ec' } }
      },
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

  /**
   * 城市对比柱状图（F6）：按均值升序，最干净的排在最左。
   * @param {Array} stats A3 返回的统计数组
   * @param {string} metric 当前指标键
   */
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
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: function (params) {
          var p = params[0];
          var row = sorted[p.dataIndex];
          return row.city + '<br/>均值 ' + p.value + unit +
            '<br/>最大 ' + (row.max === null || row.max === undefined ? '—' : row.max) +
            ' · 最小 ' + (row.min === null || row.min === undefined ? '—' : row.min) +
            '<br/>优良率 ' + (row.goodRatio === null || row.goodRatio === undefined ? '—' : row.goodRatio) + '%';
        }
      },
      grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
      xAxis: {
        type: 'category',
        data: sorted.map(function (row) { return row.city; }),
        axisLabel: { fontSize: 12, color: '#4b5563' },
        axisLine: { lineStyle: { color: '#d1d5db' } }
      },
      yAxis: {
        type: 'value',
        name: meta.unit,
        nameTextStyle: { fontSize: 11, color: '#6b7280' },
        axisLabel: { fontSize: 12, color: '#4b5563' },
        splitLine: { lineStyle: { color: '#f1f0ec' } }
      },
      series: [{
        type: 'bar',
        barMaxWidth: 34,
        data: sorted.map(function (row) {
          return {
            value: (row.avg === null || row.avg === undefined) ? 0 : Number(row.avg),
            itemStyle: { color: palette[dominantLevel(row)] || '#9ca3af' }
          };
        })
      }]
    };
  }

  /**
   * 等级分布环形图（F7）：把各城市的 levelCounts 累加成整体分布。
   * @param {Array} stats A3 返回的统计数组
   */
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
      tooltip: {
        trigger: 'item',
        formatter: function (p) { return p.name + '<br/>' + p.value + ' 天（' + p.percent + '%）'; }
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
        data: data
      }]
    };
  }

  /** 由 app.js 调用：有数据则渲染，无数据则清空并交给调用方显示空态 */
  function renderTrend(data) {
    instances.trend.show(buildTrend(data));
  }

  function renderCompare(stats, metric) {
    instances.compare.show(buildCompare(stats, metric));
  }

  function renderPie(stats) {
    instances.pie.show(buildPie(stats));
  }

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
    renderCompare: renderCompare,
    renderPie: renderPie,
    resizeAll: function () {
      Object.keys(instances).forEach(function (key) { instances[key].resize(); });
    },
    instances: instances
  };
})(window);
