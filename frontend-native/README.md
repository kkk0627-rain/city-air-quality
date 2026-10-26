# 原生版前端（HTML + CSS + JavaScript）

课程要求明确：**前端使用原生 HTML + CSS + JavaScript（ES6+），通过 fetch 调用后端接口**，
不得使用 Vue / React / axios 等框架。**本目录即该要求的合规实现，答辩时以本目录为准。**

> 关于 `frontend/`（Vue 3 + Vite 版）：它是项目早期的实现，功能与本目录等价，
> 现保留仅作两种技术栈的对照参考，**不作为课程要求的交付物**。
> 若担心评分时造成混淆，可直接删除 `frontend/` 目录，本目录不受任何影响。

它与 `frontend/`（Vue 3 + Vite 版）**功能完全等价**，目录并列存在，互不影响，
便于对照两种技术栈的实现差异。

## 目录结构

```
frontend-native/
├── index.html          # 页面骨架，只提供挂载点，不含业务数据
├── css/style.css       # 全部样式（由 Vue 版各组件 scoped 样式合并而来）
├── js/api.js           # fetch 版 API 层：Result 剥壳、指标/等级元信息
├── js/charts.js        # ECharts 实例管理 + 11 张图表的 option 构造
├── js/app.js           # 状态编排、DOM 渲染、事件绑定
├── vendor/echarts.min.js  # 本地 ECharts（离线可用，不依赖 CDN）
└── server.js           # 零依赖 Node 静态服务器，默认 5174
```

## 启动

**前提**：后端已在 8080 端口运行，且 MySQL 中已有 `aqi_dashboard` 库。

```bash
node server.js        # 或 node server.js 5175 换端口
```

然后浏览器打开 <http://127.0.0.1:5174/>。

> 为什么不能直接双击 `index.html`？
> 以 `file://` 打开时浏览器把来源视为 `null`，后端 `CorsConfig` 不会放行，
> 所有接口都会被拦。必须通过 HTTP 服务访问。
> 端口 5174 是后端 `CorsConfig` 已显式放行的来源之一，无需改后端配置。

## 与后端的对接

| 需求 | 接口 | 实现位置 |
| --- | --- | --- |
| A1 城市列表 | `GET /api/cities` | `api.js` → `getCities()` |
| A2 明细分页 | `GET /api/air-quality` | `api.js` → `queryAirQuality()` |
| A3 分组统计 | `GET /api/air-quality/stats` | `api.js` → `queryStats()` |
| A4 单条详情 | `GET /api/air-quality/{id}` | `api.js` → `getRecord()` |
| — 趋势序列 | `GET /api/air-quality/series` | `api.js` → `querySeries()` |
| — 数据集元信息 | `GET /api/air-quality/meta` | `api.js` → `getMeta()` |
| — 城市概览 | `GET /api/aqi/overview` | `api.js` → `getOverview()` |
| A5 综合分析 | `GET /api/air-quality/analysis` | `api.js` → `getAnalysis()` |

前端用 `fetch` 直连 `http://127.0.0.1:8080/api`，**走真实跨域**，
由后端的 `CorsFilter` 放行。若后端端口变化，在 `index.html` 加载 `api.js`
之前设置 `window.AQI_API_BASE` 即可覆盖。

## 功能对照

| 编号 | 功能 | 实现 |
| --- | --- | --- |
| F1 | 看板总览布局（标题栏 + 横向筛选条 + 6 张 KPI 卡 + 四个页签） | `index.html` + `style.css` |
| F2 | 城市多选 | `app.js` → `renderCityChips()` |
| F3 | 指标切换 | `app.js` → `renderMetricChips()` |
| F4 | 时间范围筛选 | `app.js` → `renderRangeInputs()` + 快捷区间 |
| F5 | 多城趋势折线 | `charts.js` → `buildTrend()` |
| F6 | 城市对比柱状 | `charts.js` → `buildCompare()` |
| F7 | 等级分布环图 | `charts.js` → `buildPie()` |
| F8 | 数据详情表格 | `app.js` → `renderTable()`（每页 10 条） |

### 11 张图按四个页签分组

| 页签 | 图表 | 构造函数 |
| --- | --- | --- |
| 总览 | 逐日趋势折线(F5) · 等级环形(F7) · 月度走势 | `buildTrend` / `buildPie` / `buildMonth` |
| 对比 | 综合指数排名 · 六项污染物雷达 · 城市对比柱(F6) | `buildRank` / `buildRadar` / `buildCompare` |
| 分类 | 区域 · 季节 · 首要污染物 · 六档等级天数 · 城市×月份热力图 | `buildRegion` / `buildSeason` / `buildPollutant` / `buildLevelBar` / `buildHeatmap` |

**综合指数 CI** = `PM2.5/35 + PM10/70 + SO2/60 + NO2/40 + CO/4 + O3/160`，
**越小越清洁**，六项同时计入，弥补 AQI"取最大值、一项否决"的缺陷。
排名图与热力图的城市顺序都按 CI 升序，读图时"越靠上越干净"。

"对比"页签底部的**城市档案表**支持点表头排序（排名/城市/区域/综合指数/AQI/PM2.5/PM10/O₃/优良率），
前三名带奖牌样式。

## 几个刻意的实现细节

1. **`connectNulls: false`**：缺测日期传 `null`，折线必须断开，否则会把空洞连成虚假趋势。
2. **柱子配色取「众数等级」**：用 `levelCounts` 最大的一档，而不是按均值反推等级——
   后者对 PM2.5 这类浓度值没有意义（等级分段是 AQI 标度，不是 μg/m³）。
3. **`getLevelMeta` 与 `getLevelMetaByLevel` 分开**：前者入参是 AQI 数值，
   后者是等级编号。混用会把 `level=3` 当成 `AQI=3`，错判成"优"。
4. **日期一律手工拼 `yyyy-MM-dd`**：`toISOString()` 按 UTC 输出，在 UTC+8 环境下
   会把本地 00:00 推回前一天，导致整个区间偏移一天。
5. **图表首次 `show()` 时才 `init()`**：容器若处于 `display:none` 的父节点中，
   提前 init 会取到 0 宽高，画不出来。
6. **切换指标会立即重载，不必再点"查询"**：若沿用 Vue 版「标签先更新、数据等查询」的做法，
   会停在"高亮 PM2.5、表头仍写 AQI、数字仍是 AQI 均值"的自相矛盾状态。
   城市与日期仍维持点"查询"才加载——前者是多选、后者可能输入到一半。
7. **综合指数不复用 AQI 的口径**：AQI 是"六项分指数取最大"，只要 PM2.5 一项超标就拉高，
   无法比较城市整体优劣；CI 是六项浓度比限值之和，能反映"整体差多少"。
   实测海口 CI=1.81 排第 1，而 AQI 均值最低的是深圳（40.8）——两者给出的结论不同，
   正是 CI 提供的额外信息。
8. **热力图城市顺序按 CI 排名而非名称**：12 城 × 12 月共 144 格，若按名称或入库顺序排，
   读图时看不出规律；按 CI 升序排后，"上面一行整体偏绿、下面一行整体偏红"，
   区域差异一眼可见。
9. **切回页签必须 `resize`**：隐藏容器宽度为 0，ECharts 会按 0 宽高渲染。
   `switchTab()` 里用 `setTimeout(…, 30)` 让浏览器先完成布局再 resize，
   直接同步调用仍可能读到 0。

## 验证情况

接口层与页面渲染均已用 Node + jsdom 跑过冒烟测试，对接真实后端：

- API 层 **25/25 通过**：A1/A2/A3/A4、meta、series、概览，
  含翻页不重复、记录不存在与后端不可达两类错误处理；
- DOM 渲染 **30/30 通过**：12 城 chips、7 指标、KPI 四张卡、
  10 行明细表 + 分页（第 1/36 页 · 共 360 条）、焦点城市卡、
  三张图表的option（12 条曲线 / 断线设置 / 30 天 X 轴 / 升序对比 / 环形等级）、
  点击行弹 A4 详情、指标切换联动、清空城市后按钮禁用。

改版后追加的验证：

- **A5 综合分析接口**：拉真实响应逐字段核对，`cities`(12) / `monthly`(144) /
  `monthlyTrend`(12) / `regions`(7) / `seasons`(4) / `pollutants`(5) / `levels`(6) 齐全；
- **8 张新增图表**：用真实响应在 Node 里跑一遍 option 构造，
  全部产出合法 option，无 `NaN` / `null` / `undefined`；
- **挂载点一致性**：`app.js` 的 `IDS` 列表与 `index.html` 的 `id` 逐一比对，无缺失。
