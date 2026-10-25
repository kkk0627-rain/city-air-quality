# 任务 18 ~ 28 验收报告

**日期**：2026-10-22
**范围**：按新功能规格（F1~F8 / A1~A4）补齐缺口
**结论**：✅ 全部通过（后端 `mvn clean test` 46/46 BUILD SUCCESS，前端 `npm run build` 636 模块通过）

---

## 1. 用户拍板的两个前提

| 问题 | 决定 | 影响 |
|---|---|---|
| 城市代码口径 | **复用现有 `city.id`** | 不新增列、不做迁移脚本；响应字段名仍为 `code`。将来换国标行政区划码只需改 Service 一行映射 |
| 新接口放哪 | **新建 `AirQualityQueryController`** | 既有 `/api/aqi/*` 三条契约不动，零回归风险 |

---

## 2. 交付物清单

| # | 文件 | 内容 |
|---|---|---|
| 18 | `docs/dataset.md` 第 11 节 | A1~A4 契约 + 三个口径决议 |
| 19 | `backend/.../mapper/AirQualityMapper.java` | +5 方法签名 |
| 20 | `backend/.../resources/mapper/AirQualityMapper.xml` | +5 条 SQL，含可复用 WHERE 片段 |
| 21 | `backend/.../service/AirQualityService.java` | +4 方法（含分页/统计/序列/元信息），`CityBriefVO` 收敛为 `{code,name,province,region}` |
| 22 | `backend/.../controller/AirQualityQueryController.java` | A2 / A3 / A4 / meta / series |
| 23 | `frontend/src/api/airQuality.js` | 5 个接口方法 + `METRICS` |
| 24 | `frontend/src/components/FilterPanel.vue` | F2 + F3 + F4 |
| 25 | `frontend/src/components/LevelPieChart.vue` | F7 |
| 26 | `frontend/src/components/CityCompareChart.vue` + 重写 `AqiTrendChart.vue` | F6 + F5 |
| 27 | `frontend/src/components/DataDetailTable.vue` | F8 |
| 28 | `frontend/src/App.vue` | F1 重组 |

---

## 3. 后端接口实测

### A1 `GET /api/cities`
```
HTTP 200
{"code":0,"msg":"ok","data":[
  {"code":1,"name":"北京","province":"北京市","region":"华北"},
  {"code":2,"name":"石家庄","province":"河北省","region":"华北"}, …]}
```

### A2 `GET /api/air-quality?cityIds=1,2&metric=pm25&start=2025-12-01&end=2025-12-31&page=1&size=10`
```
HTTP 200
{"code":0,"msg":"ok","data":{
  "total":62,"page":1,"size":10,
  "list":[{"id":4321,"code":1,"city":"北京","date":"2025-12-31",
           "metric":"pm25","value":7,"unit":"μg/m³",
           "aqi":19,"level":1,"levelLabel":"优"}, …]}}
```
第 2 页返回 `page:2` 且首行日期为 `2025-12-26`，翻页正确。CO 指标返回 `value:0.6 / unit:"mg/m³"`，小数保留正常。

### A3 `GET /api/air-quality/stats?metric=pm25`
```
{"code":0,"msg":"ok","data":[
  {"code":1,"city":"北京","metric":"pm25","unit":"μg/m³",
   "avg":27.2,"max":158,"min":3,
   "goodRatio":89.5,"goodDays":323,"samples":361,
   "levelCounts":{"1":169,"2":154,"3":30,"4":7,"5":1,"6":0}}, …]}
```

### A4 `GET /api/air-quality/{id}`
```
HTTP 200
{"code":0,"msg":"ok","data":{"id":1,"code":1,"city":"北京","date":"2025-01-01",
  "pm25":23,"pm10":48,"so2":3,"no2":37,"co":0.5,"o3":28,
  "aqi":48,"level":1,"levelLabel":"优"}}
```

### 错误分支

| 用例 | HTTP | 响应 |
|---|---|---|
| `GET /api/air-quality/99999999` | 404 | `{"code":404,"msg":"记录不存在: id=99999999"}` |
| `metric=pm99` | 400 | `{"code":400,"msg":"不支持的指标: pm99，可选 aqi/pm25/pm10/so2/no2/co/o3"}` |
| `start=2025-12-31&end=2025-01-01` | 400 | `{"code":400,"msg":"起始日期不能晚于截止日期…"}` |

### CORS

| 用例 | 结果 |
|---|---|
| `Origin: http://localhost:5173` | 200 ✅ |
| `Origin: http://evil.example.com` | **403，且响应无 `Access-Control-Allow-Origin`** ✅ |

---

## 4. 三个值得记住的判断

**① 日期计算不能用 `toISOString()`。**
它按 UTC 输出，在 UTC+8 下会把本地 `2025-12-31T00:00` 转成 UTC `2025-12-30T16:00`，`slice(0,10)` 拿到 `2025-12-30`——**整体偏一天**。统一改成手工拼 `yyyy-MM-dd`。

**② 统计放 Java 侧聚合，而不是 SQL `GROUP BY`。**
理由：① 上限 4,332 行，内存聚合开销可忽略；② 避免在 XML 里用 `${metric}` 拼列名，即便白名单也是安全隐患；③ "优良率 = level≤2" 必须与 `levelOf()` 单一实现，否则 SQL 和 Java 会分裂成两份逻辑。
**意外收获**：这样 Mapper XML 里一条 `GROUP BY` 都没有，原有的"全部走索引直查"约束完整保住了——之前担心 F6 会破坏它。

**③ 改 VO 前先 grep 一遍引用。**
`CityBriefVO` 从 `{id,cityName}` 改成 `{code,name}` 后，`AirQualityControllerTest` 里 `$.data[0].cityName` 的断言会挂，已同步改成 `$.data[0].name` 才跑通。

---

## 5. 遗留项

- `AqiRankingChart.vue`（任务 16，"最新一日横向快照榜"）在新页面未挂载。它与 F6 的"时段平均"是两个不同问题：
  榜单回答"今天谁最脏"，对比柱状图回答"这一个月谁最脏"。如需保留榜单，在 `App.vue` 里加回即可，接口与组件都已就绪。
- 新增的 `/meta`、`/series` 两个端点不在原始 A1~A4 清单里，是为支撑 F5 多城市趋势与 F3 日期边界而补充的，已在 `dataset.md` 第 11.2 节说明。
