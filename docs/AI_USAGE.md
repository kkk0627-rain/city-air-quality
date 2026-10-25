# AI 使用记录表

> 课程提交物之一。如实记录本项目各环节中 AI 的参与方式、产出的内容，
> 以及**人工复核时发现的问题和所做的修改**。
>
> AI 工具：WorkBuddy（对话式编码助手，内置 Claude 模型）
> 项目周期：2026-10-19 ~ 2026-10-25

---

## 一、总览

| 环节 | AI 参与程度 | 说明 |
| --- | --- | --- |
| 数据集获取与清洗 | 高（生成脚本，人工审定口径） | 清洗规则由人工提出，AI 写脚本实现 |
| 数据库设计 | 中（初稿 + 人工调整索引） | 表结构由人工按字段口径确定 |
| 后端业务代码 | 高（分层代码由 AI 编写） | 每一处均对照 `docs/dataset.md` 的契约复核 |
| 单元测试 | 高（用例由 AI 生成） | 46/46 通过，边界值由人工指定 |
| 前端实现 | 高（Vue 版与原生版均由 AI 编写） | 原生版改用 fetch 后由人工逐项冒烟验证 |
| 运行脚本 / 文档 | 中 | 机器环境差异由人工实测后修正 |
| 演示 PPT | 中（初稿 16 页 → 人工压到 7 页） | 页数与叙事结构由人工按评分要求定 |
| 数据分析结论 | 低（AI 出图，结论人工核验） | 所有数字回查 `dataset/dataset_meta.txt` |

---

## 二、逐项记录

| # | 日期 | 使用环节 | 具体任务 / 关键指令 | AI 产出 | 人工复核与修改 |
| --- | --- | --- | --- | --- | --- |
| 1 | 10-19 | 数据集方案 | "下载中国环境监测总站 2025 年全年数据，12 城日粒度" | 下载脚本 + 365 个原始文件的抓取逻辑 | 改：镜像源改为 quotsoft.net（原站直连不稳定） |
| 2 | 10-19 | 数据清洗 | 按"日报口径 + 有效小时数闸门"合并 365 个文件 | `db/merge_dataset.py` | 审定清洗规则：有效小时 < 12 的日期**整日丢弃**；人工核对丢弃的 4 天（06-04/06-15/06-28/11-06）确为源头残缺 |
| 3 | 10-19 | 数据校验 | 校验产出 CSV 的规模与分布 | `dataset/dataset_meta.txt` | 人工复核 12 城 × 361 天 = 4,332 行；确认理论满额 4,380、差额 48 行 = 4 天 × 12 城，账目对得上 |
| 4 | 10-20 | 数据库设计 | 建库建表 + 索引 | `db/init.sql` | 人工补充：按高频查询路径 `(city_id, date)` 建联合索引；明确 `--default-character-set=utf8mb4`（否则 12 城中文名乱码） |
| 5 | 10-20 | 后端骨架 | Spring Boot 3.2 + JDK 17 工程初始化 | `pom.xml`、`application.yml`、启动类 | 人工修正：JDK 17 平台编码为 GBK，`pom.xml` 必须显式 `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>` |
| 6 | 10-20 | 统一响应与异常处理 | 设计 `{code,msg,data}` 与全局异常处理 | `common/Result.java`、`common/GlobalExceptionHandler.java` | 人工决策：`isSuccess()` 加 `@JsonIgnore`，避免 Lombok 多出 `success` 字段破坏三字段契约 |
| 7 | 10-21 | 持久层 | MyBatis 接口与动态 SQL | `mapper/AirQualityMapper.java` + `AirQualityMapper.xml` | 人工核对每条 SQL 的索引命中；指标列按 `metric` 白名单动态选择，防 SQL 注入 |
| 8 | 10-21 | 业务逻辑 | AQI 换算、等级判定、统计与分页 | `service/AirQualityService.java` | 人工核对 HJ 633-2012 分段表；AQI=500 确认为沙尘导致 PM10 超国标上限后封顶，属真实事件非脏数据 |
| 9 | 10-22 | 接口层 | A1~A4 四个接口 | `controller/AirQualityQueryController.java` 等 | 人工统一参数名为 `cityIds/start/end/page/size`（AI 初稿曾写成 `cityCodes/startDate`，已改） |
| 10 | 10-22 | 单元测试 | 边界值与路由测试 | 46 个测试用例 | 人工指定边界：AQI 0/50/51/100/101/150/151/200/201/300/301；46/46 通过 |
| 11 | 10-22 | 前端（Vue 版） | 看板页面与图表组件 | `frontend/src/**` | 人工修正三处：折线 `connectNulls:false`（否则缺测连成虚假趋势）；柱子取"众数等级"而非按均值反推；`getLevelMeta` 与等级编号查询函数分开 |
| 12 | 10-22 | 日期处理 | 区间计算 | 多处日期工具函数 | 人工修正：禁用 `toISOString()`（UTC+8 下会把本地 00:00 推回前一天），统一手工拼 `yyyy-MM-dd` |
| 13 | 10-25 | 运行脚本 | 一键启动 | `start.cmd` | 用户实测失败后人工修 v2：自动探测 `npm`/`mysql` 路径（本机 Node 不在系统 PATH）；端口占用即跳过 |
| 14 | 10-25 | 演示 PPT | 项目介绍 | 初稿 **16 页** | **人工压到 7 页**——老师要求 5~8 页，16 页直接违规 |
| 15 | 10-25 | 前端改原生 | "前端改成原生 HTML+CSS+JS+fetch" | `frontend-native/**`（api.js / charts.js / app.js / server.js） | 见下方"重点案例 A" |
| 16 | 10-25 | Swagger | 接入 OpenAPI 文档 | `pom.xml` 依赖、`config/OpenApiConfig.java`、Controller 注解 | 见下方"重点案例 B" |
| 17 | 10-25 | 文档 | README / 运行指南 / 本记录表 | `README.md`、`docs/RUN.md`、`docs/AI_USAGE.md` | 人工核对所有数字（4,332 行、均值 62.3、优良率 89.0%）均回查 `dataset_meta.txt` |

---

## 三、重点案例（AI 产出 ≠ 可直接交付）

### 案例 A：原生前端的"指标切换不联动"缺陷

AI 产出原生版前端后，我用 jsdom 加载真实页面跑冒烟测试（30 项），发现：

- **现象**：点击 PM2.5 后 chip 高亮了，但表格表头仍显示 "AQI"，页面停在
  **"高亮 PM2.5 / 表头 AQI / 数字是 AQI 均值"** 的自相矛盾状态。
- **根因**：AI 沿用了 Vue 版"标签立即更新、数据等点击查询"的响应式写法，
  但原生版没有响应式系统，标签只在拉到新数据后才会刷新。
- **修改**：改为切换指标后**立即重载数据**（`js/app.js` 的指标 chip 点击事件）。
  城市与日期仍保留"点查询才加载"——前者是多选、后者可能输入到一半。

同类由测试发现并修掉的问题还有：图表容器在 `display:none` 时提前 `init()` 会取到 0 宽高
（改为首次 `show()` 时才 `init`）。

### 案例 B：Swagger UI 路径被拼成两段

- **AI 产出**：`application.yml` 里写 `springdoc.swagger-ui.path: /swagger-ui/index.html`。
- **现象**：访问该路径返回 302，跳转到 `/swagger-ui/swagger-ui/index.html`（路径重复）。
- **根因**：springdoc 把 `path` 当**基址**，会再拼上 `swagger-ui/index.html`。
- **修改**：删掉这个覆盖项，用 springdoc 默认行为——
  `/swagger-ui.html` 302 跳到 `/swagger-ui/index.html`（实测 200）。

### 案例 C：数据集"今天"不是系统今天

- AI 一开始按系统当前日期（2026 年）初始化前端日期控件，导致一进页面就落在**没有数据的区间**。
- **修改**：新增 `/api/air-quality/meta` 接口，把数据集真实边界（2025-01-01 ~ 2025-12-31）
  下发给前端，日期控件按 `min/max` 锁定，默认值取数据集末日往前 30 天。

### 案例 D：一键启动脚本在本机必然失败

- AI 初版脚本直接调 `npm run dev`，但**本机没有独立安装 Node.js**，
  `node/npm` 只在 WorkBuddy 内置目录且不在系统 PATH，双击脚本必然报
  `'npm' 不是内部或外部命令`。
- **修改**：脚本增加三段式探测（PATH → `C:\Program Files\nodejs` → 遍历
  `%USERPROFILE%\.workbuddy\binaries\node\versions\*`）。
  注意 `versions\current` 是一个**文件**不是目录，所以必须用 `for /d` 遍历版本目录。

---

## 四、使用原则（本项目的自我约束）

1. **AI 不替代数据核验**：所有对外展示的数字（4,332 行、均值 62.3、优良率 89.0%、首要污染物分布）
   一律回查 `dataset/dataset_meta.txt` 与数据库 `COUNT(*)` 交叉验证，不采信 AI 凭记忆给出的数。
2. **AI 产出必经运行验证**：后端 46/46 单测；前端 API 层 25/25、DOM 渲染 30/30 冒烟；
   接口逐一 curl 验证过。
3. **不隐藏缺陷**：上表"人工复核与修改"一栏刻意保留了 4 个案例的完整失败过程，
   因为它们比"一次成功"更能说明项目的真实工程含量。
4. **AI 未独立决策的部分**：数据集选题与城市名单、评分口径（HJ 633-2012）、
   PPT 页数与叙事结构、以及"前端必须原生"这一技术路线的最终取舍，均由人工判断。
