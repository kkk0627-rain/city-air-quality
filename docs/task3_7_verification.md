# 任务 3 ~ 任务 7 验收报告

- **验收日期**：2026-10-20
- **环境**：Windows 11 / JDK 17.0.20.1 / Maven 3.9.9 / Node 22.22.2 / MySQL 8.0
- **结论**：5 个任务全部通过，共 **14 个单元测试通过**，前后端均可真实启动并访问。

---

## 1. 环境准备（本轮新增，不计入任务数）

本机原本**没有 Maven**，需要先装。过程中踩到的坑按可复用程度记录如下。

| 事项 | 处理 | 原因 |
|---|---|---|
| Maven 未安装 | 装到 `D:\apache-maven-3.9.9` | C 盘空间紧张，不放默认位置 |
| 本地仓库位置 | `D:/m2repo/repository`（写入 `~/.m2/settings.xml`） | 依赖缓存约 150~250 MB，避免撑爆 C 盘 |
| 镜像源 | **只用 `repo.maven.apache.org`** | 实测阿里、清华、腾讯、archive.apache.org 的镜像 **DNS 或连接全部失败**；只有 Maven Central 可直连 |
| 下载方式 | 用 `curl`（走系统代理），**不用 Python `urllib`** | urllib 不走代理，对同一批域名报 `getaddrinfo failed` / SSL `UNEXPECTED_EOF` |
| 大文件下载 | `curl -C -` 断点续传 | 首次下载被截断在 6.4 MB / 9.2 MB，补传一次即完整 |

Maven 发行包直接从 Maven Central 取（该域名可达）：
`https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip`

---

## 2. 任务 3：后端工程骨架

**交付文件**

| 文件 | 说明 |
|---|---|
| `backend/pom.xml` | Spring Boot 3.2.5 父 POM、Java 17、MyBatis 3.0.3、mysql-connector-j、validation、Lombok、test |
| `backend/src/main/resources/application.yml` | 数据源、端口、MyBatis、Jackson、日志 |
| `backend/src/main/java/com/aqi/dashboard/AqiDashboardApplication.java` | 启动类 |

> **文件数说明**：任务清单里任务 3 是"配置文件对"（`pom.xml` + `application.yml`），但只有这两个文件应用**无法启动**，
> 验证条件"`mvn spring-boot:run` 启动成功"就落不了地。因此补上启动类，本任务实为 3 个文件。

**验证结果**

```
[INFO] Compiling 4 source files with javac [debug release 17] to target\classes
[INFO] BUILD SUCCESS
Tomcat initialized with port 8080 (http)
Started AqiDashboardApplication in 7.79 seconds
```

- `mvn -B compile` → BUILD SUCCESS，4 个源文件编译通过
- `mvn spring-boot:run` → 应用在 **8080** 启动，耗时 7.79 秒，日志无 `HikariPool` 报错

**配置要点**

- `project.build.sourceEncoding=UTF-8` 是**必需**的：本机 JDK 17 默认平台编码为 **GBK**（`mvn -v` 可见 `platform encoding: GBK`），不写会导致中文注释编译告警甚至报错。
- 数据源：`jdbc:mysql://localhost:3306/aqi_dashboard`，密码写成 `${DB_PASSWORD:123456}`，可用环境变量覆盖。
- `map-underscore-to-camel-case: true`，让数据库 `monitor_date` 自动映射到 `monitorDate`（任务 8 实体类直接受益）。
- `mapper-locations: classpath:mapper/*.xml` 已预留给任务 10 的 `AirQualityMapper.xml`。

**⚠️ 环境陷阱：`SERVER__PORT` 会劫持端口**

启动曾失败两次，报错都是：

```
Web server failed to start. Port 57544 was already in use.
```

端口不是配置里的 8080，而是 **57544**。排查发现环境变量里存在：

```
SERVER__PORT = 57544
SERVER__HOST = 127.0.0.1
```

Spring Boot 的宽松绑定把它当成了 `server.port`，而**环境变量优先级高于 `application.yml`**。
对照实验：清掉这两个变量后启动 → `Tomcat initialized with port 8080`，问题消失，根因确认。

在这个环境里跑后端，任选一种方式：

```powershell
# 方式 A：命令行参数（优先级最高，推荐）
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"

# 方式 B：先清掉环境变量
Remove-Item Env:\SERVER__PORT; mvn spring-boot:run
```

**在你自己的终端里不受影响**（那里没有这个变量），直接 `mvn spring-boot:run` 即可。

---

## 3. 任务 4：前端工程骨架

**交付文件**

| 文件 | 说明 |
|---|---|
| `frontend/package.json` | Vue 3.4、ECharts 5.5、axios 1.7、Vite 5.4、@vitejs/plugin-vue |
| `frontend/vite.config.js` | 5173 端口、`/api` 代理到 8080、ECharts 分包 |
| `frontend/index.html` | 挂载点 |
| `frontend/src/main.js` | 应用入口 |
| `frontend/src/App.vue` | 骨架占位（任务 17 会用真实布局替换本文件） |

**验证结果**

```
added 61 packages in 4m
vite v5.4.21 building for production...
✓ 12 modules transformed.
✓ built in 4.32s
VITE v5.4.21  ready in 1289 ms
➜  Local:   http://127.0.0.1:5173/
HTTP GET http://127.0.0.1:5173/ -> 200
```

- `npm install` → 61 个包安装成功
- `npm run build` → 构建成功（4.32 秒）
- `npm run dev` → 5173 端口就绪，HTTP 访问返回 **200**，页面标题与挂载点正确

> 构建时有一条警告 `Generated an empty chunk: "echarts"`，属**预期**：当前还没有组件 import echarts。
> 等任务 15/16 的图表组件接入后该警告自动消失。

---

## 4. 任务 5：统一返回体

**交付文件**：`backend/src/main/java/com/aqi/dashboard/common/Result.java`

结构 `{code, msg, data}`，状态码 `0 / 400 / 404 / 500` 与 HTTP 语义对齐。

**验证**：`ResultTest` — **8 个测试全部通过**

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

覆盖：`ok()`、`ok(data)`、`ok(data,msg)`、`fail(msg)` 默认 500、`fail(code,msg)`、
`badRequest`/`notFound` 快捷方法、四个常量值、`data` 为 null 时 `toString()` 不抛异常。

**一处修正**：初版 `isSuccess()` 会被 Jackson 序列化成额外的 `success` 字段，实测响应为

```json
{"code":404,"msg":"接口不存在: /api/__probe","success":false}
```

与 `dataset.md` 第 9 节的 `{code, msg, data}` 三字段契约不符。已加 `@JsonIgnore` 排除，
现在实际响应为：

```json
{"code":404,"msg":"接口不存在: /api/__probe"}
```

`data` 为 null 时被 `spring.jackson.default-property-inclusion: non_null` 省略，契约干净。

---

## 5. 任务 6：全局异常处理

**交付文件**：`backend/src/main/java/com/aqi/dashboard/common/GlobalExceptionHandler.java`

**验证**：`GlobalExceptionHandlerTest` — **6 个测试全部通过**

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0   (总计)
```

| 场景 | 断言 |
|---|---|
| 正常请求 | 200 + `code=0`，不被异常处理器误拦截 |
| `IllegalArgumentException` | 400 + 回显业务原因 |
| 缺少必需参数 | 400，msg 含参数名 `cityId` |
| 参数类型不匹配 | 400，不抛裸异常 |
| `IllegalStateException` | 500，且**不泄露**内部异常信息 |
| 兜底 `Exception` | 500，msg 固定为"服务器内部错误，请稍后重试"，**不含**原始异常文本 |

用 MockMvc `standaloneSetup` 装配一个测试内部类 `ProbeController` 触发异常，
**不启动 Tomcat、不连数据库**，可独立运行。

**真实 HTTP 验证**（比单测更有说服力）：

```
GET http://127.0.0.1:8080/api/__probe
→ 404 {"code":404,"msg":"接口不存在: /api/__probe"}
```

说明 `NoHandlerFoundException` 分支在生产链路里同样生效（依赖 `application.yml` 中
`spring.mvc.throw-exception-if-no-handler-found: true`）。

**关于"业务异常"**：为遵守"一个任务只改一个文件"，未新增 `BusinessException` 类。
业务校验失败统一用 `IllegalArgumentException`（400）/ `IllegalStateException`（500）。
任务 11 若需区分业务错误码，在本类追加一个 `@ExceptionHandler` 分支即可，不影响其它文件。

---

## 6. 任务 7：跨域配置

**交付文件**：`backend/src/main/java/com/aqi/dashboard/config/CorsConfig.java`

**验证**（真实 OPTIONS 预检）：

```
OPTIONS http://127.0.0.1:8080/api/cities
Origin: http://localhost:5173
Access-Control-Request-Method: GET

→ 200
  Access-Control-Allow-Origin:  http://localhost:5173
  Access-Control-Allow-Methods: GET,POST,OPTIONS
```

**设计取舍**：用 `CorsFilter` 而非 `WebMvcConfigurer#addCorsMappings`。
原因是过滤器在链最前端生效，能覆盖被全局异常处理接管后的错误响应——
错误响应若不带 CORS 头，浏览器会把 4xx/5xx 统一报成 CORS 错误，排查时极易误判。

来源显式列出 `localhost:5173` / `127.0.0.1:5173`（及 5174 备用），**不用 `*`**：
`*` 与 `allowCredentials` 互斥，后续若要接入 Cookie 认证会直接报错。

---

## 7. 当前服务状态

两个服务都还在运行，可以直接访问：

| 服务 | 地址 | 状态 |
|---|---|---|
| 后端 | http://127.0.0.1:8080 | 运行中（8080） |
| 前端 | http://127.0.0.1:5173 | 运行中，HTTP 200 |

前端 `/api` 已配置代理到后端，联调时可直接用相对路径发请求。

---

## 8. 本轮新增/变更的文件

```
新增：
  backend/pom.xml
  backend/src/main/resources/application.yml
  backend/src/main/java/com/aqi/dashboard/AqiDashboardApplication.java
  backend/src/main/java/com/aqi/dashboard/common/Result.java
  backend/src/main/java/com/aqi/dashboard/common/GlobalExceptionHandler.java
  backend/src/main/java/com/aqi/dashboard/config/CorsConfig.java
  backend/src/test/java/com/aqi/dashboard/common/ResultTest.java          (验证件)
  backend/src/test/java/com/aqi/dashboard/common/GlobalExceptionHandlerTest.java (验证件)
  frontend/package.json
  frontend/vite.config.js
  frontend/index.html
  frontend/src/main.js
  frontend/src/App.vue
  db/install_maven.py      (环境工具)
  db/maven_setup.py        (环境工具)
  db/diag_port.py          (环境工具)
  C:\Users\Lenovo\.m2\settings.xml
  docs/task3_7_verification.md

变更：
  db/install_maven.py  → 改为多镜像兜底 + 证书放宽
```

---

## 9. 下一步

任务 8 ~ 10（实体类 → Mapper 接口 → Mapper XML）是**纯串行关键路径**，无可并行空间。
任务 8 依赖已导入的 `air_quality` / `city` 两张表，字段口径见 `docs/dataset.md` 第 5 节。

---

## 10. 独立复验（2026-10-20 14:10，第二轮）

上一节是开发当时的验证。本节是**重新跑一遍**的复核，脚本为 `db/verify_task3_7.py`（文件清点 + 数据库基线）
与 `db/verify_live.py`（真实 HTTP 行为），不复用开发时的中间结果。

### 10.1 交付文件清点 — 缺失 0

| 任务 | 文件数 | 状态 |
|---|---|---|
| 任务 3 后端骨架 | 3（`pom.xml` 114 行 / `application.yml` 57 行 / 启动类 18 行） | 全部存在 |
| 任务 4 前端骨架 | 5（`package.json`、`vite.config.js`、`index.html`、`main.js`、`App.vue`） | 全部存在 |
| 任务 5 统一返回体 | 1（`Result.java` 121 行） | 存在 |
| 任务 6 全局异常处理 | 1（`GlobalExceptionHandler.java` 162 行） | 存在 |
| 任务 7 跨域配置 | 1（`CorsConfig.java` 55 行） | 存在 |
| 验证件 | 3（两个测试类 + 本报告） | 存在 |

### 10.2 单元测试 — clean 重建后 14/14 通过

`mvn clean test` 从零重新编译，结果：

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- GlobalExceptionHandlerTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- ResultTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

> 日志中出现的两条 `ERROR ... GlobalExceptionHandler` 是**预期输出**——
> 测试故意触发 500 场景，用来验证"兜底记录 error 日志"这条分支，不是测试失败。

### 10.3 数据库基线 — 完好

| 检查项 | 结果 |
|---|---|
| `air_quality` 行数 | 4332 |
| `city` 行数 / 去重 city_id | 12 / 12 |
| 日期范围 | 2025-01-01 ~ 2025-12-31 |
| 等级分布 | 1:1906 / 2:1950 / 3:374 / 4:59 / 5:35 / 6:8 |

与任务 2 导入时完全一致，任务 3 的数据源配置指向的库是对的。

### 10.4 线上行为 — 6 项全通过

| 编号 | 检查 | 结果 |
|---|---|---|
| V1 | 前端 5173：HTTP 200、标题"城市空气质量看板"、含 `<div id="app">` 与 `/src/main.js` | 通过 |
| V2 | 后端 404 响应体字段集合**恰好是** `['code','msg']`，无 `success` 冗余 | 通过 |
| V3 | CORS 正向：`Origin=localhost:5173` → 200，`Allow-Origin` / `Allow-Methods` / `Max-Age:3600` 齐全 | 通过 |
| V4 | **CORS 反向**：`Origin=evil.example.com` → **403，且无 `Allow-Origin` 头** | 通过 |
| V5 | 端口确为 8080（未被 `SERVER__PORT` 劫持） | 通过 |
| V6 | 前端 `/api` 代理：`GET 5173/api/__probe` → 后端统一错误体，代理链路通 | 通过 |

V4 是本次新增的反向用例——只证明"该放行的放行了"是不够的，
还要证明"不该放行的没放行"。`CorsFilter` 对未授权来源直接返回 403 且不带放行头，
说明来源白名单是真正生效的，而不是无脑放行。
