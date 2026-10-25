# -*- coding: utf-8 -*-
"""
清洗入库脚本（任务 2 交付物）：把 D:\\airdata\\raw 的原始 CSV 清洗为 db/init.sql。

流程:
  1. 遍历 2025-01-01 ~ 2025-12-31 共 365 个原始文件
  2. 质量闸门: 有效小时数 < 12 的日期整体丢弃（源头数据残缺，日均值不可靠）
  3. 提取 12 城的日报口径统计量（PM2.5_24h/PM10_24h/SO2_24h/NO2_24h/CO_24h/O3_8h_24h）
  4. 按 HJ 633-2012 日均分段表计算 AQI、等级、首要污染物
  5. 生成建库建表 + INSERT 的 init.sql，并输出 import_report.txt 统计报告

用法: python clean_build_sql.py [--mindays 12] [--out ..\\db\\init.sql]
"""
import sys
import argparse
from pathlib import Path
from datetime import date, timedelta

sys.path.insert(0, str(Path(__file__).parent))
from extract_daily import parse_day, daily_aqi, round_for_storage, CITIES  # noqa: E402

RAW = Path(r"D:\airdata\raw")
DB_NAME = "aqi_dashboard"

# 城市元数据: (id, 城市, 省份, 区域, 经度, 纬度)
CITY_META = [
    (1, "北京", "北京市", "华北", 116.40, 39.90),
    (2, "石家庄", "河北省", "华北", 114.51, 38.04),
    (3, "哈尔滨", "黑龙江省", "东北", 126.53, 45.80),
    (4, "郑州", "河南省", "中原", 113.62, 34.75),
    (5, "上海", "上海市", "华东", 121.47, 31.23),
    (6, "杭州", "浙江省", "华东", 120.15, 30.27),
    (7, "广州", "广东省", "华南", 113.26, 23.13),
    (8, "深圳", "广东省", "华南", 114.06, 22.55),
    (9, "成都", "四川省", "西南", 104.07, 30.57),
    (10, "西安", "陕西省", "西北", 108.94, 34.34),
    (11, "兰州", "甘肃省", "西北", 103.83, 36.06),
    (12, "海口", "海南省", "华南", 110.32, 20.03),
]

INT_FIELDS = ("pm25", "pm10", "so2", "no2", "o3")


def sql_value(v, field):
    if v is None:
        return "NULL"
    if field == "co":
        return f"{round(float(v), 1):.1f}"
    return str(int(round(float(v))))


def build(min_hours: int, out_path: Path):
    days = [date(2025, 1, 1) + timedelta(days=i) for i in range(365)]
    city_id = {name: cid for cid, name, *_ in CITY_META}

    rows = []                 # (city_id, date_str, pm25, pm10, so2, no2, co, o3, aqi, primary, level)
    dropped_days = []         # (date, hours)
    missing_city_days = 0
    files_missing = 0
    aqi_all = []
    level_count = {1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0}
    primary_count = {}

    for d in days:
        f = RAW / f"china_cities_{d.strftime('%Y%m%d')}.csv"
        if not f.exists():
            files_missing += 1
            dropped_days.append((d, 0))
            continue
        data, hours = parse_day(str(f))
        if hours < min_hours:
            dropped_days.append((d, hours))
            continue
        for city in CITIES:
            rec = data.get(city)
            if not rec:
                missing_city_days += 1
                continue
            stored = round_for_storage(rec)   # AQI 基于入库精度计算，保证可从库内复算
            aqi, level_label, primary = daily_aqi(stored)
            if aqi is None:
                missing_city_days += 1
                continue
            level = ["优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"].index(level_label) + 1
            rows.append((
                city_id[city], d.isoformat(),
                stored.get("pm25"), stored.get("pm10"), stored.get("so2"),
                stored.get("no2"), stored.get("co"), stored.get("o3"),
                aqi, primary, level,
            ))
            aqi_all.append(aqi)
            level_count[level] += 1
            primary_count[primary or "无"] = primary_count.get(primary or "无", 0) + 1

    # ---- 生成 SQL ----
    lines = []
    lines.append("-- 城市空气质量看板 · 数据库初始化脚本")
    lines.append("-- 数据来源: 中国环境监测总站（全国城市空气质量实时发布平台）")
    lines.append("-- 清洗口径: HJ 633-2012 日报（日均值统计量）  生成脚本: db/clean_build_sql.py")
    lines.append(f"-- 数据量: 12 城 × {len(rows)} 行")
    lines.append("")
    lines.append(f"CREATE DATABASE IF NOT EXISTS {DB_NAME} "
                 "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    lines.append(f"USE {DB_NAME};")
    lines.append("")
    lines.append("DROP TABLE IF EXISTS air_quality;")
    lines.append("DROP TABLE IF EXISTS city;")
    lines.append("")
    lines.append("""CREATE TABLE city (
  id         INT PRIMARY KEY AUTO_INCREMENT,
  city_name  VARCHAR(20)  NOT NULL COMMENT '城市名',
  province   VARCHAR(20)  NOT NULL COMMENT '省份',
  region     VARCHAR(20)  NOT NULL COMMENT '所属地理区',
  longitude  DECIMAL(5,2)          COMMENT '经度',
  latitude   DECIMAL(4,2)          COMMENT '纬度'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市表';""")
    lines.append("")
    city_vals = ",\n".join(
        f"  ({cid}, '{name}', '{prov}', '{reg}', {lon}, {lat})"
        for cid, name, prov, reg, lon, lat in CITY_META)
    lines.append("INSERT INTO city (id, city_name, province, region, longitude, latitude) VALUES")
    lines.append(city_vals + ";")
    lines.append("")
    lines.append("""CREATE TABLE air_quality (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  city_id           INT            NOT NULL COMMENT '城市 ID',
  monitor_date      DATE           NOT NULL COMMENT '监测日期',
  pm25              SMALLINT UNSIGNED          COMMENT 'PM2.5 24h 均值, ug/m3',
  pm10              SMALLINT UNSIGNED          COMMENT 'PM10 24h 均值, ug/m3',
  so2               SMALLINT UNSIGNED          COMMENT 'SO2 24h 均值, ug/m3',
  no2               SMALLINT UNSIGNED          COMMENT 'NO2 24h 均值, ug/m3',
  co                DECIMAL(4,1)               COMMENT 'CO 24h 均值, mg/m3',
  o3                SMALLINT UNSIGNED          COMMENT 'O3 8h 滑动均值当日最大, ug/m3',
  aqi               SMALLINT UNSIGNED NOT NULL COMMENT '日报 AQI',
  primary_pollutant VARCHAR(8)                 COMMENT '首要污染物, AQI<=50 为 NULL',
  level             TINYINT UNSIGNED NOT NULL  COMMENT '等级 1-6: 优/良/轻度/中度/重度/严重',
  UNIQUE KEY uk_city_date (city_id, monitor_date),
  KEY idx_date (monitor_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市空气质量日报';""")
    lines.append("")

    batch = 500
    for i in range(0, len(rows), batch):
        chunk = rows[i:i + batch]
        lines.append("INSERT INTO air_quality "
                     "(city_id, monitor_date, pm25, pm10, so2, no2, co, o3, aqi, primary_pollutant, level) VALUES")
        vals = []
        for r in chunk:
            cid, dstr, pm25, pm10, so2, no2, co, o3, aqi, primary, level = r
            prim = "NULL" if primary is None else f"'{primary}'"
            vals.append(f"  ({cid}, '{dstr}', {sql_value(pm25, 'pm25')}, {sql_value(pm10, 'pm10')}, "
                        f"{sql_value(so2, 'so2')}, {sql_value(no2, 'no2')}, {sql_value(co, 'co')}, "
                        f"{sql_value(o3, 'o3')}, {aqi}, {prim}, {level})")
        lines.append(",\n".join(vals) + ";")
        lines.append("")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")

    # ---- 统计报告 ----
    per_city = {}
    for r in rows:
        per_city[r[0]] = per_city.get(r[0], 0) + 1

    report = []
    report.append("=== 清洗入库报告 ===")
    report.append(f"原始文件扫描: 365 天，缺失文件 {files_missing} 个")
    report.append(f"质量闸门（有效小时 < {min_hours}）丢弃日期: {len(dropped_days)} 天")
    for d, h in dropped_days:
        report.append(f"  - {d}（有效小时 {h}）")
    report.append(f"城市-日空值丢弃: {missing_city_days} 行")
    report.append(f"最终入库行数: {len(rows)}（理论满额 4,380）")
    report.append("")
    report.append("各城市行数:")
    for cid, name, *_ in CITY_META:
        report.append(f"  {name}: {per_city.get(cid, 0)} 行")
    report.append("")
    if aqi_all:
        report.append(f"AQI: 最小 {min(aqi_all)}，最大 {max(aqi_all)}，均值 {sum(aqi_all)/len(aqi_all):.1f}")
    report.append("等级分布:")
    for lv, label in enumerate(["优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"], start=1):
        report.append(f"  {lv} {label}: {level_count[lv]} 行")
    report.append("首要污染物分布:")
    for k in sorted(primary_count, key=lambda x: -primary_count.get(x, 0)):
        report.append(f"  {k}: {primary_count[k]} 行")
    report.append("")
    report.append("北京 2025-01-08 ~ 01-14 七日样本（供文档校验趋势图形态）:")
    for r in rows:
        if r[0] == 1 and "2025-01-08" <= r[1] <= "2025-01-14":
            report.append(f"  {r[1]}  AQI={r[8]}  等级={r[10]}  首要={r[9] or '无'}  "
                          f"PM2.5={r[2]}  PM10={r[3]}  O3={r[7]}")

    rep_path = out_path.parent / "import_report.txt"
    rep_path.write_text("\n".join(report), encoding="utf-8")
    print("\n".join(report))
    print(f"\nSQL 已生成: {out_path}（{out_path.stat().st_size/1024:.0f} KB）")
    print(f"报告已生成: {rep_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minhours", type=int, default=12, help="日期有效小时数门槛，默认 12")
    ap.add_argument("--out", default=str(Path(__file__).parent / "init.sql"))
    args = ap.parse_args()
    build(args.minhours, Path(args.out))


if __name__ == "__main__":
    main()
