# 城市空气质量看板 · 运行指南

面向"换一台机器从零跑起来"的完整步骤。按本文顺序执行，5 分钟内可看到页面。

---

## 0. 环境要求

| 组件 | 版本要求 | 本机实测版本 | 检查命令 |
|---|---|---|---|
| JDK | **17**（Spring Boot 3.2 强制） | 17.0.20.1 | `java -version` |
| Maven | 3.6+ | 3.9.9 | `mvn -v` |
| Node.js | **18+**（`package.json` 的 engines 字段） | v22.22.2 | `node -v` |
| npm | 随 Node | 10.9.7 | `npm -v` |
| MySQL | **8.0** | 8.0（3306） | `mysql --version` |

> 后端首次构建需要联网下载依赖（Spring Boot + MyBatis + MySQL 驱动，约 60~100 MB）。
> 前端 `node_modules` 已随项目存在；若拷贝时排除了它，需要补一次 `npm install`。

---

## 1. 启动 MySQL 并导入数据

### 1.1 确认 MySQL 在 3306 监听

```bash
netstat -ano | findstr :3306
```

没有输出说明服务没起。Windows 上用 `services.msc` 启动 `MySQL80`，或：

```cmd
net start MySQL80
```

### 1.2 导入初始化脚本

`db/init.sql` 自带 `CREATE DATABASE` 和 `USE`，一条命令即可，**不需要先手动建库**：

```cmd
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p --default-character-set=utf8mb4 -e "source C:\Users\Lenovo\WorkBuddy\web城市空气质量看板\db\init.sql"
```

> **务必带 `--default-character-set=utf8mb4`**，否则 12 个城市的中文名会变成乱码。
> 如果 SQL 路径含中文导致 `source` 失败，先把 `init.sql` 复制到纯英文路径（如 `D:\init.sql`）再导入。

### 1.3 验证导入结果

```cmd
"...\mysql.exe" -u root -p -e "SELECT COUNT(*) FROM aqi_dashboard.air_quality; SELECT COUNT(*) FROM aqi_dashboard.city;"
```

期望：**air_quality = 4332，city = 12**。

---

## 2. 配置数据库密码

后端 `backend/src/main/resources/application.yml` 第 22 行：

```yaml
password: ${DB_PASSWORD:123456}
```

密码优先读环境变量 `DB_PASSWORD`，没设才回退到 `123456`。

**推荐做法（不改动受版本控制的文件）**：启动前设环境变量

```cmd
set DB_PASSWORD=你的密码
```

PowerShell：

```powershell
$env:DB_PASSWORD="你的密码"
```

密码真的就是 `123456` 的话，这一步可以跳过。

---

## 3. 启动后端（端口 8080）

```cmd
cd C:\Users\Lenovo\WorkBuddy\web城市空气质量看板\backend
mvn.cmd spring-boot:run
```

看到这三行才算成功：

```
Tomcat started on port(s): 8080 (http)
Started AqiDashboardApplication in X.XXX seconds
```

> **Windows 上请用 `mvn.cmd`，不要用 Git Bash 里的 `mvn`。**
> 后者是 shell 脚本，在 Git Bash 下会报
> `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。
> Maven 不在 PATH 里时写全路径：`D:\apache-maven-3.9.9\bin\mvn.cmd`。

### 可选：跳过测试

```cmd
mvn.cmd spring-boot:run -DskipTests
```

（`spring-boot:run` 默认不跑测试，通常无需此参数。）

### 可选：打成 jar 运行

```cmd
mvn.cmd clean package -DskipTests
java -jar target\aqi-dashboard.jar
```

---

## 4. 启动前端 · Vue 版（端口 5173）

> 这是项目早期的 Vue 3 + Vite 实现，保留作技术栈对照。
> **课程要求交付的是原生版**，见下一节 4.5。

**另开一个终端**（后端那个要一直挂着）：

```cmd
cd C:\Users\Lenovo\WorkBuddy\web城市空气质量看板\frontend
npm run dev
```

输出 `Local: http://localhost:5173/` 即成功。若提示缺依赖：

```cmd
npm install
```

### 4.1 报 `'npm' 不是内部或外部命令` 怎么办

本机**没有独立安装 Node.js**，`node` / `npm` 只存在于 WorkBuddy 自带的二进制目录里
（`C:\Users\Lenovo\.workbuddy\binaries\node\versions\22.22.2-3\`），不在系统 PATH 中。
所以直接在 cmd 里敲 `npm` 会失败，而 Git Bash / WorkBuddy 终端里却能跑——很容易误判。

两个办法，任选其一：

**A. 临时把目录加进当前窗口的 PATH（推荐，不改系统设置）**

```cmd
set "PATH=%USERPROFILE%\.workbuddy\binaries\node\versions\22.22.2-3;%PATH%"
npm run dev
```

**B. 永久加进系统 PATH**

`Win + R` → `sysdm.cpl` → 高级 → 环境变量 → 用户变量 `Path` → 新建一行：

```
C:\Users\Lenovo\.workbuddy\binaries\node\versions\22.22.2-3
```

保存后**重开终端**才生效。之后 `npm`、`node` 在全局可用。

> `start.cmd` 已经内置了自动探测（会依次找 PATH、`C:\Program Files\nodejs`、
> `.workbuddy\binaries\node\versions\*`），双击启动不需要手动设 PATH。
> 只有在**手动**敲命令时才需要上面这一步。
>
> 如果这台机器要长期用 Node 开发，建议从 https://nodejs.org/ 装一个官方 LTS 版，
> 比依赖内置目录更省心；装完上面两种办法就都不需要了。

`mysql` 也是同样情况——它在 `C:\Program Files\MySQL\MySQL Server 8.0\bin`，
同样不在 PATH。手动导入数据时写全路径即可，`start.cmd` 也会自动补上。

---

## 4.5 启动前端 · 原生版（端口 5174）

课程要求前端为**原生 HTML + CSS + JavaScript + fetch**，这一版在 `frontend-native/`，
无需 `npm install`、无需构建，只需要一个静态服务器。

**一键启动**：双击项目根目录的 `start-native.cmd`
（它会自己找 `node.exe`，并在 8080 没起来时顺带拉起后端）。

**手动启动**：

```cmd
cd C:\Users\Lenovo\WorkBuddy\web城市空气质量看板\frontend-native
node server.js
```

然后打开 **http://127.0.0.1:5174/**。

> **为什么不能双击 `index.html`？**
> 以 `file://` 打开时浏览器把来源视为 `null`，后端 `CorsConfig` 不会放行，
> 所有接口都会被拦。必须走 HTTP 服务。
> 端口 5174 已在后端 `CorsConfig` 的白名单里（`localhost:5174` / `127.0.0.1:5174`），
> 所以**不需要改任何后端配置**；`server.js` 也刻意**不代理** `/api`，
> 让浏览器真实跨域访问 8080，正好能验证 CORS 配置确实生效。
>
> 如果 5174 被占用：`node server.js 5175`，但记得同步把端口加进
> `backend/src/main/java/com/aqi/dashboard/config/CorsConfig.java` 的 `ALLOWED_ORIGINS`。

`node` 不在 PATH 时（同 4.1），先执行：

```cmd
set "PATH=%USERPROFILE%\.workbuddy\binaries\node\versions\22.22.2-3;%PATH%"
node server.js
```

---

## 5. 打开看板

| 版本 | 地址 | 说明 |
| --- | --- | --- |
| 原生版 | **http://127.0.0.1:5174** | 课程要求的交付版本 |
| Vue 版 | http://localhost:5173 | 参考实现，功能等价 |

> 走前端地址，不要直接开 8080。后端 CORS 白名单只放行了前端来源；
> 直接访问 8080 只会拿到裸 JSON（或跨域报错）。

页面默认展示**近 30 天（2025-12-02 ~ 2025-12-31）**。注意数据集的"今天"是 **2025-12-31**，
不是系统当前日期——日期选择器已按 `min=2025-01-01 / max=2025-12-31` 锁定范围。

---

## 6. 一键自检

请求都应返回 200 或正常 JSON（原生版 / Vue 版二选一，不用都跑）：

```bash
REM 原生版：静态页 200，接口由浏览器直连 8080
curl -s -o /dev/null -w "native   :%{http_code}\n" http://127.0.0.1:5174/
curl -s -o /dev/null -w "echarts  :%{http_code}\n" http://127.0.0.1:5174/vendor/echarts.min.js
curl -s -i -H "Origin: http://127.0.0.1:5174" http://127.0.0.1:8080/api/cities | findstr /i "Access-Control-Allow-Origin"

REM Vue 版：/api 由 Vite 代理
curl -s -o /dev/null -w "frontend:%{http_code}\n" http://localhost:5173/
curl -s -o /dev/null -w "proxy   :%{http_code}\n" http://localhost:5173/api/cities

curl -s http://localhost:8080/api/cities
curl -s "http://localhost:8080/api/air-quality?cityIds=1,2&metric=pm25&start=2025-01-01&end=2025-01-05&page=1&size=3"
```

> **A2 接口的参数名是 `cityIds` / `start` / `end` / `page` / `size`**，
> 不是 `cityCodes` / `startDate` / `endDate` / `pageSize`。
> 传错名字不会报错，参数会被静默忽略并返回全量 4332 行——很容易误判成"筛选失效"。

其余接口：

```bash
curl -s "http://localhost:8080/api/air-quality/stats?cityIds=1&metric=aqi&start=2025-01-01&end=2025-03-31"
curl -s http://localhost:8080/api/air-quality/100
curl -s http://localhost:8080/api/air-quality/meta
```

---

## 7. 常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `Communications link failure` | MySQL 没起，或库没导入 | 回到第 1 步，`net start MySQL80` |
| `Access denied for user 'root'` | 密码不对 | 设 `DB_PASSWORD` 环境变量，或改 yml 第 22 行 |
| `Unknown database 'aqi_dashboard'` | init.sql 没导入 | 重跑第 1.2 步 |
| 城市名显示成 `???` | 导入时没指定字符集 | 加 `--default-character-set=utf8mb4` 重新导入 |
| `Port 8080 was already in use` | 端口被占 | `netstat -ano \| findstr :8080` 找到 PID 后 `taskkill /PID <pid> /F` |
| `Port 5173 is already in use` | 同上（vite 开了 strictPort） | 同上，或改 `vite.config.js` 的 port |
| `找不到或无法加载主类 ...Launcher` | 用了 Git Bash 的 `mvn` | 改用 `mvn.cmd` |
| `'npm' 不是内部或外部命令` | Node 只装在 WorkBuddy 内置目录，不在系统 PATH | 见 4.1 节；或直接双击 `start.cmd` |
| `'mysql' 不是内部或外部命令` | MySQL bin 不在 PATH | 写全路径 `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |
| 原生版打开后满屏`无法连接后端服务` | 后端没起，或 8080 被占 | 先确认 8080 在监听，再刷新页面 |
| 原生版接口全是 CORS 报错 | 直接双击了 `index.html`（来源为 `null`） | 必须用 `node server.js` 走 HTTP 访问，见 4.5 节 |
| `EADDRINUSE` on 5174 | 静态服务器端口被占 | `node server.js 5175`，并同步加进 `CorsConfig` 白名单 |
| `Unsupported character encoding 'utf8mb4'` | yml 里 JDBC 的 `characterEncoding` 写错 | 必须是 `UTF-8`（服务端字符集名才是 utf8mb4） |
| 页面能开但图表空白 | 后端没起，或日期区间落在 2025 年之外 | 看浏览器 Network 面板；确认日期在 2025-01-01 ~ 2025-12-31 |
| 后端监听的不是 8080 | 环境变量 `SERVER__PORT` 劫持了 `server.port` | `set SERVER__PORT=` 清空后重启（仅沙箱环境会出现） |
| 图表日期比选的早一天 | 代码里用了 `toISOString()`（UTC 偏移） | 已修复：统一手工拼 `yyyy-MM-dd` |

---

## 8. 生产部署（可选）

```cmd
cd frontend
npm run build
```

产物在 `frontend/dist`。两种用法：

1. **静态托管**：把 `dist` 丢给 Nginx，用 Nginx 反向代理 `/api` 到后端 8080。
2. **打进 jar**：把 `dist` 内容复制到 `backend/src/main/resources/static/`，
   再 `mvn package`。注意此时 `add-mappings: false`（yml 第 41 行）会禁用静态资源映射，
   需要改回 `true` 或改用 Spring 的 `WebMvcConfigurer` 显式注册。

---

## 9. macOS / Linux 等价命令

```bash
# 导入数据
mysql -u root -p --default-character-set=utf8mb4 -e "source ./db/init.sql"

# 后端
cd backend && DB_PASSWORD=你的密码 ./mvnw spring-boot:run   # 或 mvn spring-boot:run

# 前端
cd frontend && npm run dev
```

其余步骤完全一致。
