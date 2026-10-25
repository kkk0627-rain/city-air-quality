# 任务 8 ~ 17 验收报告

**验收时间**：2026-10-21
**范围**：后端数据链路（实体 → Mapper → XML → Service → Controller）+ 前端四层（API → 三组件 → 页面组装）
**结论**：全部通过。46 个自动化测试 + 6 项线上 HTTP 校验 + 前端构建与模块编译，均无失败。

---

## 1. 交付文件清单

| 任务 | 交付文件 | 行数 | 说明 |
|---|---|---|---|
| 8 | `backend/.../entity/City.java` | 47 | 对应 city 表 6 列 |
| 8 | `backend/.../entity/AirQuality.java` | 76 | 对应 air_quality 表 12 列 + 非表字段 cityName |
| 9 | `backend/.../mapper/AirQualityMapper.java` | 76 | 5 个方法，附索引走法说明 |
| 10 | `backend/src/main/resources/mapper/AirQualityMapper.xml` | 79 | 5 条 SQL，均为索引直查无聚合 |
| 11 | `backend/.../service/AirQualityService.java` | 375 | 国标换算 + 看板聚合 + 嵌套 VO record |
| 12 | `backend/.../controller/AirQualityController.java` | 92 | 4 个 GET 接口 |
| 13 | `frontend/src/api/air.js` | 90 | axios 实例 + 4 方法 + 六档配色常量 |
| 14 | `frontend/src/components/CityOverview.vue` | 148 | 概览卡（纯展示，数据由 App 传入） |
| 15 | `frontend/src/components/AqiTrendChart.vue` | 179 | 折线图，visualMap 六档分段上色 |
| 16 | `frontend/src/components/AqiRankingChart.vue` | 141 | 横向条形排行榜，逐条按等级着色 |
| 17 | `frontend/src/App.vue` | 331 | 布局与数据编排，替换原骨架占位 |

配套验证文件（非任务交付物，常驻可重跑）：
`AirQualityServiceTest.java`（11 例，纯单测）、`AirQualityMapperIntegrationTest.java`（13 例，连真实库）、`AirQualityControllerTest.java`（8 例，MockMvc + Mockito）。

---

## 2. 自动化测试：`mvn clean test` → **46 / 46 通过**

```
GlobalExceptionHandlerTest          Tests run: 6,  Failures: 0, Errors: 0
ResultTest                          Tests run: 8,  Failures: 0, Errors: 0
AirQualityControllerTest            Tests run: 8,  Failures: 0, Errors: 0
AirQualityMapperIntegrationTest     Tests run: 13, Failures: 0, Errors: 0
AirQualityServiceTest               Tests run: 11, Failures: 0, Errors: 0
总计                                Tests run: 46 → BUILD SUCCESS
```

### 任务 11 纯单测（11 例）

| 编号 | 用例 | 结果 |
|---|---|---|
| T1 | 等级边界 0/50/51/100/101/150/151/200/201/300/301/500 | ✅ |
| T2 | 六档标签文本与 dataset.md 4.3 一致 | ✅ |
| T3 | IAQI 锚点：pm25=35、pm10=50、so2=50、no2=40、co=2、o3=100 均返回 50 | ✅ |
| T4 | 中段插值：PM2.5=55 → IAQI 75 | ✅ |
| T5 | 边界：0 浓度→0，超上限→500 封顶 | ✅ |
| T6 | 未知污染物抛 IllegalArgumentException | ✅ |
| T7 | 已知真实行复算：北京 2025-01-01 → AQI 48 优，首要污染物为 null | ✅ |
| T8 | 首要污染物阈值：AQI=50 无 / AQI=51 出现 PM2.5 | ✅ |
| T9 | 取整模式：IAQI 52.5 → **52**（HALF_EVEN） | ✅ |
| T10 | 六项全缺抛 IllegalStateException | ✅ |
| T11 | 部分缺失按可见项计算 | ✅ |

### 任务 9/10/11 集成测试（13 例，直连 MySQL）

| 编号 | 用例 | 结果 |
|---|---|---|
| M1 | findAllCities 返回 12 城，驼峰映射 city_name → cityName | ✅ |
| M2 | findMaxDate = 2025-12-31 | ✅ |
| M3 | findLatestByCity(1) 命中最新日，AQI 19 | ✅ |
| M3b | 不存在的城市返回 null | ✅ |
| M4 | findTrend 12 月区间 31 行且日期连续升序 | ✅ |
| M4b | **6 月区间仅 27 行**，自动跳过清洗阶段丢弃的 3 天 | ✅ |
| M5 | findRankingByDate 返回 12 城同日快照、AQI 升序、cityName 非空 | ✅ |
| S1 | getOverview 组装概览卡字段 | ✅ |
| S2 | 城市不存在返回 null | ✅ |
| S3 | 三种窗口点数：7d=7、30d=30、12m=361，末点为最新日 | ✅ |
| S4 | 非法 range 抛 IllegalArgumentException（且先于查库） | ✅ |
| S5 | getRanking 返回 12 城升序榜单，标签与 AQI 自洽 | ✅ |
| X1 | **4,332 行全量交叉校验：Java 复算 vs 入库值 零偏差** | ✅ |

### 任务 12 契约测试（8 例，MockMvc standalone + Mockito）

C1 cities / C2 overview / C3 城市不存在→404 / C4 trend 默认 30d / C4b range 透传 / C5 ranking / C6 缺参数→400 / C7 类型错误→400，全部通过。

---

## 3. 线上 HTTP 校验（后端 8080 运行中）

| 请求 | 响应 |
|---|---|
| `GET /api/cities` | 200，12 项，字段恰为 `{id, cityName, province, region}` |
| `GET /api/aqi/overview?cityId=1` | 200，`{"cityName":"北京","monitorDate":"2025-12-31","aqi":19,"level":1,"levelLabel":"优"}`，AQI≤50 时省略 `primaryPollutant` |
| `GET /api/aqi/overview?cityId=10` | 200，`西安 119 轻度污染 PM2.5`（含首要污染物） |
| `GET /api/aqi/trend?cityId=1&range=7d` | 200，7 个点，末点 2025-12-31 |
| `GET /api/aqi/trend?cityId=11&range=12m` | 200，361 个点，首点 2025-01-01 |
| `GET /api/aqi/ranking` | 200，12 行升序：北京 19 → 西安 119 |
| `GET /api/aqi/overview?cityId=999` | **404** `{"code":404,"msg":"城市不存在: id=999"}` |
| `GET /api/aqi/trend?cityId=1&range=90d` | **400** `{"code":400,"msg":"不支持的区间: 90d，可选 7d/30d/12m"}` |
| `GET /api/aqi/trend`（缺 cityId） | **400** `{"code":400,"msg":"缺少必需参数: cityId"}` |
| `http://127.0.0.1:5173/api/cities`（经 Vite 代理） | 200，链路通 |

日期字段序列化结果为 `"2025-12-31"` 字符串，非时间戳数组，与前端 X 轴口径一致。

---

## 4. 前端校验

- `npm run build`：**629 个模块转换成功，20.08s**，产物 index.js 61 KB / vue 66 KB / echarts 1.03 MB（已按 manualChunks 拆包）；
- Vite dev 5173 逐个请求模块，App.vue 与三个组件、api/air.js 均返回 200，无编译错误；
- Vite `/api` 代理 → 后端 8080 实测连通（见上表最后一行）。

---

## 5. 过程中发现并修掉的三个真问题

### 5.1 JDBC 字符集写错（潜伏到任务 9 才暴露）

`application.yml` 原写 `characterEncoding=utf8mb4`，集成测试报
`Unsupported character encoding 'utf8mb4'`。

`utf8mb4` 是 **MySQL 服务端**字符集名，`characterEncoding` 要的是 **Java 字符集名**，
Connector/J 会把 `UTF-8` 自动映射到服务端 utf8mb4。已改为 `characterEncoding=UTF-8`。
此前数据不出岔子是因为入库走的是 mysql 客户端（任务 2），JDBC 从未真正建过连接。

### 5.2 舍入模式必须跟随入库脚本（对照实验验证）

入库数据由 Python `round()` 产生，Python 的 round 是 **half-even**（银行家舍入），
而 Java 的 `Math.round` 是 half-up。为验证这是不是"想多了"，做了一次对照实验：

| 取整模式 | 4,332 行交叉校验结果 |
|---|---|
| `HALF_EVEN`（最终采用） | **零偏差** |
| `HALF_UP` | 大量 ±1 偏差，例如 北京 2025-03-15 库值 52 / 算出 53、2025-01-09 库值 26 / 算出 27 |

结论：**HALF_EVEN 不是洁癖，是必须**。否则"从库里复算"这条自洽性保证在 Java 侧会崩掉。

### 5.3 `/api/cities` 字段超出契约

初版直接返回 `City` 实体，多出 `longitude` / `latitude` 两个预留字段，
与 dataset.md 第 9 节的 `[{id, cityName, province, region}]` 不符。
已新增嵌套 record `CityBriefVO` 收敛，服务相关规定见下节。

---

## 6. 两处需要知悉的设计取舍

1. **VO 写成 Service 的嵌套 record**（`OverviewVO` / `TrendPointVO` / `RankItemVO` / `CityBriefVO`）。
   这是为守住"一个任务只改一个文件"的约束：若每个 VO 独立成文件，任务 11 会变成 5 个文件。
   record 不可变且可直接被 Jackson 序列化，Controller 以 `AirQualityService.OverviewVO` 引用。
   后续若要拆出，把 record 剪切成同包下的独立文件即可，调用方加一条 import。

2. **城市不存在用 ResponseEntity(404) 而非自定义异常**。
   任务 6 的 GlobalExceptionHandler 里没有 business exception 分支，为了不再动那个文件，
   改由 Controller 在 Service 返回 null 时输出 404，保证 HTTP status 与 body 的 `code` 一致。
   代价是 Service 的 `getOverview` / `getTrend` 对未知城市返回 null（已在 javadoc 注明）。

---

## 7. 复现命令

```bash
# 后端：全部测试（需 MySQL 8 运行且已导入 db/init.sql）
mvn -B -f backend/pom.xml clean test

# 后端：启动（本机无 SERVER__PORT 注入时）
mvn -B -f backend/pom.xml spring-boot:run

# 前端：安装 / 构建 / 开发
cd frontend && npm install && npm run build && npm run dev
```

浏览器打开 http://127.0.0.1:5173 即可看到完整看板。

---

## 8. 剩余待办

任务清单中的 15 个任务已全部完成（1~17，其中任务 3/4 各为配置文件对）。
可选的后续工作：TrendPointVO 增加 30 天/12 个月的标记（当前由前端过滤）、
组件级单测、数据表升级到小时粒度以恢复十万行量级（dataset.md 第 1 节有方案）。
