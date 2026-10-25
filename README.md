# 城市空气质量看板

一个基于**真实监测数据**的城市空气质量可视化看板：后端 Spring Boot 3 + MyBatis 提供
统一 REST 接口，前端用**原生 HTML + CSS + JavaScript（ES6+）+ fetch** 直连接口，
ECharts 渲染趋势、对比与等级分布。

数据集来自中国环境监测总站 2025 年全年日均值，**12 个城市 × 361 天 = 4,332 条**记录，
AQI 与等级严格按国标 HJ 633-2012 计算。

---

## 一、项目简介

| 项 | 内容 |
| --- | --- |
| 数据规模 | 12 城 × 361 天 = **4,332 条**（课程要求 ≥ 450 条，超出约 9.6 倍） |
| 时间范围 | 2025-01-01 ~ 2025-12-31（数据集的"今天"是 2025-12-31，不是系统当前日期） |
| AQI 口径 | `AQI = max(PM2.5, PM10, SO₂, NO₂, CO, O₃ 的 IAQI)`，HJ 633-2012 日均分段表 |
| 整体 AQI | 最小 13，最大 500，均值 **62.3** |
| 优良天数占比 | **89.0%**（优 1906 + 良 1950，共 3856 / 4332） |
| 最清洁 / 最污染 | 深圳 40.8 / 郑州 82.4（年均 AQI） |
| 首要污染物分布 | 无 1906 · O₃ 926 · PM10 722 · PM2.5 640 · NO₂ 138（天） |

---

## 二、功能清单

| 编号 | 功能 | 实现位置 |
| --- | --- | --- |
| F1 | 看板总览布局（标题栏 + 左侧筛选 + 右侧图表区） | `frontend-native/index.html` + `css/style.css` |
| F2 | 城市多选（12 城，支持全选/清空） | `js/app.js` → `renderCityChips()` |
| F3 | 指标切换（AQI / PM2.5 / PM10 / SO₂ / NO₂ / CO / O₃） | `js/app.js` → `renderMetricChips()` |
| F4 | 时间范围筛选（日期控件 + 近 7/30/90 天、全年快捷区间） | `js/app.js` → `renderRangeInputs()` |
| F5 | 多城市趋势折线图 | `js/charts.js` → `buildTrend()` |
| F6 | 城市对比柱状图（按时段均值升序） | `js/charts.js` → `buildCompare()` |
| F7 | 空气质量等级分布环形图（六档国标配色） | `js/charts.js` → `buildPie()` |
| F8 | 数据详情表格（每页 10 条，翻页 + 行点击看详情） | `js/app.js` → `renderTable()` |

---

## 三、技术栈

**后端**

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17.0.20 | Spring Boot 3 的强制要求 |
| Spring Boot | 3.2.5 | Web MVC + 内嵌 Tomcat |
| MyBatis | 3.0.3 | XML 映射，见"关于持久层选型" |
| MySQL | 8.0 | Connector/J 驱动 |
| Maven | 3.9.9 | 构建 |
| springdoc-openapi | 2.5.0 | Swagger / OpenAPI 3 文档 |

**前端（原生实现，无框架）**

| 组件 | 说明 |
| --- | --- |
| HTML / CSS / JavaScript（ES6+） | 无 Vue / React / 无构建步骤 / 无 `npm install` |
| `fetch` | 替代 axios，直连后端 `http://127.0.0.1:8080/api` |
| ECharts 5 | 本地 `vendor/echarts.min.js`，离线可用不依赖 CDN |
| Node | 仅用于 `server.js` 这个零依赖静态服务器（不是运行时依赖） |

**分层**：Controller → Service → Mapper(Repository) → MySQL，
另有 `common/Result` 统一响应体、`common/GlobalExceptionHandler` 全局异常处理、
`config/CorsConfig` 跨域配置、`config/OpenApiConfig` 接口文档配置。

> **关于持久层选型**：课程要求"JPA 或 MyBatis-Plus"，本项目使用的是**原生 MyBatis**。
> 原因是本项目的查询全部是**动态列 + 多值 IN + 区间 + 分组统计**
> （同一个接口按 `metric` 参数查询七种不同的指标列），
> MyBatis 的 XML 动态 SQL 对此表达力最直观，也便于逐条核对 SQL 与索引命中情况。
> 若需改成 MyBatis-Plus，只需保留 `@Mapper` 接口、把 XML 里的语句换成
> `QueryWrapper` / 自定义 `@Select` 即可，**Service 与 Controller 层无需改动**。

---

## 四、快速开始

### 4.1 前置条件

- JDK 17+、Maven 3.6+、MySQL 8.0（已在 3306 运行）
- **Node 18+**：仅用于启动原生前端的静态服务器

### 4.2 一键启动（推荐）

双击项目根目录的 **`start-native.cmd`**。它会自动定位 `node.exe`、
在 8080 未监听时拉起后端、再启动原生前端，最后打开浏览器。

### 4.3 手动启动

```bash
# 1) 导入数据库（init.sql 自带 CREATE DATABASE + USE）
mysql -u root -p --default-character-set=utf8mb4 -e "source 项目路径/db/init.sql"

# 2) 启动后端（端口 8080）
cd backend
mvn spring-boot:run

# 3) 启动原生前端（端口 5174）
cd frontend-native
node server.js
```

打开 **<http://127.0.0.1:5174/>**。

> 数据库密码通过环境变量注入：`set DB_PASSWORD=你的密码`
> （`application.yml` 中默认回退为 `123456`）。

> **不要直接双击 `index.html`**：以 `file://` 打开时浏览器把来源视为 `null`，
> 后端 CORS 不放行，所有接口都会被拦。

详细的分步说明、排错表与 macOS/Linux 等价命令见 **[docs/RUN.md](docs/RUN.md)**。

---

## 五、目录结构

```
├── backend/                    Spring Boot 后端
│   └── src/main/java/com/aqi/dashboard/
│       ├── controller/         A1~A4 接口（AirQualityQueryController 为主）
│       ├── service/            AQI 换算、统计、分页、指标白名单
│       ├── mapper/             MyBatis 接口 + resources/mapper/*.xml
│       ├── entity/             AirQuality / City
│       ├── common/             Result 统一响应体 + 全局异常处理
│       └── config/             CorsConfig / OpenApiConfig
├── frontend-native/            原生前端（课程要求的交付版本）
│   ├── index.html  css/style.css
│   ├── js/api.js                fetch 版 API 层
│   ├── js/charts.js             ECharts 实例与 option
│   ├── js/app.js                状态编排与渲染
│   ├── vendor/echarts.min.js    本地 ECharts
│   └── server.js                零依赖静态服务器（5174）
├── frontend/                   Vue 3 早期版本（仅作技术栈对照，非交付物）
├── db/init.sql                  建库 + 建表 + 导入 4,332 行
├── dataset/                     清洗后的 CSV 与数据集元信息
├── docs/                        数据集说明、运行指南、AI 使用记录
├── start-native.cmd             一键启动（原生版）
└── start.cmd                    一键启动（Vue 版）
```

---

## 六、接口文档

Swagger UI（后端启动后可直接在浏览器里调试）：

- **<http://localhost:8080/swagger-ui/index.html>** —— 可交互调试页
- **<http://localhost:8080/v3/api-docs>** —— OpenAPI 3.0 规范 JSON

| 编号 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| A1 | GET | `/api/cities` | 城市列表 `[{ code, name, province, region }]` |
| A2 | GET | `/api/air-quality` | 明细分页，参数 `cityIds` / `metric` / `start` / `end` / `page` / `size` |
| A3 | GET | `/api/air-quality/stats` | 分组统计：均值 / 最大 / 最小 / 优良率 / 各等级天数 |
| A4 | GET | `/api/air-quality/{id}` | 单条记录详情 |

配套接口：`/api/air-quality/meta`（数据集日期边界与指标列表）、
`/api/air-quality/series`（多城趋势序列）、`/api/aqi/overview`（焦点城市概览）。

**统一响应结构**

```json
{ "code": 0, "msg": "ok", "data": { } }
```

`code` 为 0 表示成功；非 0 时与 HTTP 语义一致（400 参数错误 / 404 资源不存在 / 500 服务端异常）。

> A2 的参数名是 `cityIds` / `start` / `end` / `page` / `size`，
> **不是** `cityCodes` / `startDate` / `endDate` / `pageSize`。
> 传错不报错，参数被静默忽略并返回全量数据，容易误判成"筛选失效"。

**跨域**：`CorsConfig` 显式放行 `http://localhost:5173`、`http://127.0.0.1:5173`、
`http://localhost:5174`、`http://127.0.0.1:5174`（用 `CorsFilter` 而非
`WebMvcConfigurer`，保证 4xx/5xx 错误响应也带 CORS 头）。

---

## 七、数据集

| 项 | 内容 |
| --- | --- |
| 来源 | 中国环境监测总站（经 quotsoft.net 镜像下载 365 个原始文件） |
| 清洗 | 只取 12 个目标城市；只取日报口径行；**有效小时数 < 12 的日期整日丢弃** |
| 丢弃 | 4 天（2025-06-04 / 06-15 / 06-28 / 11-06），故 365 天 → 361 天 |
| 产物 | `dataset/air_quality_2025_daily.csv`（长表 4332 行）、`*_aqi_matrix.csv`（宽表） |

字段口径、IAQI 分段表、六档配色等详见 **[docs/dataset.md](docs/dataset.md)**。

---

## 八、前端说明

课程要求前端为**原生 HTML + CSS + JavaScript**，交付版本在 `frontend-native/`。
`frontend/` 是项目早期的 Vue 3 + Vite 实现，功能等价，保留作两种技术栈的对照参考，
**不作为交付物**（若担心评分混淆，可直接删除该目录，原生版不受任何影响）。

原生版的六处刻意实现细节（答辩时常被问到）记录在
**[frontend-native/README.md](frontend-native/README.md)**，包括：
缺测必须断线、柱子取"众数等级"而非均值反推、等级编号与 AQI 数值两个函数不能混用、
日期不能用 `toISOString()`、图表容器在 `display:none` 时不能提前 `init()`。

---

## 九、常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 页面红色错误条"无法连接后端服务" | 后端没起或 8080 被占，先看后端窗口是否打印 `Started AqiDashboardApplication` |
| 接口全是 CORS 报错 | 直接双击了 `index.html`，必须用 `node server.js` 走 HTTP |
| `'npm' / 'node' 不是内部或外部命令` | Node 不在系统 PATH，见 [docs/RUN.md](docs/RUN.md) 4.1 节 |
| 后端监听的不是 8080 | 环境变量 `SERVER__PORT` 劫持了 `server.port`，清空后重启 |
| 图表日期比选的早一天 | 已修复：统一手工拼 `yyyy-MM-dd`，不用 `toISOString()` |
| 城市名乱码 | 导入时漏了 `--default-character-set=utf8mb4`，重新导入 |

完整排错表见 **[docs/RUN.md](docs/RUN.md)** 第 7 节。

---

## 十、相关文档

| 文档 | 内容 |
| --- | --- |
| [docs/RUN.md](docs/RUN.md) | 完整运行指南：环境、导入、启动、自检、排错、部署 |
| [docs/dataset.md](docs/dataset.md) | 数据集来源、清洗规则、字段口径、接口契约 |
| [docs/AI_USAGE.md](docs/AI_USAGE.md) | **AI 使用记录表**（课程提交物） |
| [frontend-native/README.md](frontend-native/README.md) | 原生前端结构与实现细节 |
| [城市空气质量看板项目介绍/](城市空气质量看板项目介绍/) | 答辩 PPT（7 页）及素材 |
