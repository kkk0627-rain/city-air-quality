# 任务 2（数据库初始化）验收报告

- 验收时间：2026-10-19
- 环境：本机 MySQL 8.0（`C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`），3306 端口
- 执行方式：`mysql -u root --default-character-set=utf8mb4 -e "source ...\init.sql"`
- 建库：`aqi_dashboard`（utf8mb4 / utf8mb4_unicode_ci）

---

## 1. 导入结果

| 检查项 | 期望 | 实际 | 结论 |
|---|---|---|---|
| `air_quality` 行数 | 4,332 | **4,332** | PASS |
| `city` 行数 | 12 | **12** | PASS |
| 日期范围 | 2025-01-01 ~ 2025-12-31 | **2025-01-01 ~ 2025-12-31** | PASS |
| 各城市行数 | 各 361 | **12 城均 361** | PASS |
| 中文存储 | 城市/省份/区域正确 | **北京/北京市/华北 等正确，无乱码** | PASS |
| 等级分布 | 与 CSV 一致 | **1906/1950/374/59/35/8** | PASS |

## 2. 排行榜快照（2025-12-31，验证概览卡与排行榜取数）

```sql
SELECT c.city_name, a.aqi, a.level, IFNULL(a.primary_pollutant,'无') AS prim
FROM air_quality a JOIN city c ON c.id = a.city_id
WHERE a.monitor_date = '2025-12-31' ORDER BY a.aqi;
```

| 排名 | 城市 | AQI | 等级 | 首要污染物 |
|---|---|---|---|---|
| 1 | 北京 | 19 | 优 | 无 |
| 2 | 杭州 | 35 | 优 | 无 |
| 3 | 上海 | 36 | 优 | 无 |
| 4 | 石家庄 | 40 | 优 | 无 |
| 5 | 哈尔滨 | 40 | 优 | 无 |
| 6 | 深圳 | 48 | 优 | 无 |
| 7 | 郑州 | 52 | 良 | PM10 |
| 8 | 海口 | 63 | 良 | O3 |
| 9 | 广州 | 64 | 良 | NO2 |
| 10 | 兰州 | 75 | 良 | PM2.5 |
| 11 | 成都 | 94 | 良 | PM2.5 |
| 12 | 西安 | 119 | 轻度污染 | PM2.5 |

> 年末快照出现"优/良/轻度污染"三档、四种首要污染物，排行榜的排序与配色具备真实检验样本。

## 3. 索引与执行计划（验证三类看板查询）

| 看板查询 | SQL 形态 | 命中索引 | 扫描行数 | 结论 |
|---|---|---|---|---|
| 排行榜 | `WHERE monitor_date = '2025-12-31'` | **idx_date** | 12 | PASS |
| 趋势图 | `WHERE city_id = 1 AND monitor_date BETWEEN ...` | **uk_city_date** | 31 | PASS |
| 概览卡 | `WHERE city_id = ? AND monitor_date = (SELECT MAX(...))` | **uk_city_date**（主键级定位，MAX 子查询被优化为 `Select tables optimized away`） | 1 | PASS |

## 4. 结论

**任务 2 通过**：`db/init.sql` 可一次性建库建表并写入全部数据，中文无乱码，三类看板查询均走索引，数据与 `dataset/air_quality_2025_daily.csv`、`db/import_report.txt` 完全一致（4,332 行）。

后续任务 3（`application.yml`）的数据库连接信息：

```
url:      jdbc:mysql://localhost:3306/aqi_dashboard?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
username: root
password: <本机 MySQL root 密码>
```

> 备注：导入时为便于执行使用了命令行明文密码（MySQL 会提示 `[Warning] Using a password on the command line interface can be insecure.`）。该密码强度较弱，建议仅用于本地开发环境。
