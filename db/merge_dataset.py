# -*- coding: utf-8 -*-
"""
把 D:\\airdata\\raw 的 365 个单日原始 CSV 合并为一个完整数据集。

产出（默认写到 <项目根>/dataset/）:
  1. air_quality_2025_daily.csv      长表: 一行 = 一城一天（4,332 行），分析/入库通用
  2. air_quality_2025_aqi_matrix.csv 宽表: 一行 = 一天，列为 12 城 AQI（361 行），画图/pivot 方便
  3. dataset_meta.txt                元数据说明: 来源、口径、字段、丢弃日期、统计分布

字段口径见 docs/dataset.md: 浓度为 HJ 633-2012 日报统计量（24h 均值 / O3 8h 滑动均值当日最大），
AQI、等级、首要污染物按日均分段表计算。CSV 采用 UTF-8 with BOM，Excel 双击即可正确显示中文。

用法: python merge_dataset.py [--minhours 12] [--out <目录>]
"""
import sys
import csv
import argparse
from pathlib import Path
from datetime import date, timedelta

sys.path.insert(0, str(Path(__file__).parent))
from extract_daily import parse_day, daily_aqi, round_for_storage, CITIES  # noqa: E402

RAW = Path(r"D:\airdata\raw")

CITY_META = [
    (1, "北京", "北京市", "华北"), (2, "石家庄", "河北省", "华北"),
    (3, "哈尔滨", "黑龙江省", "东北"), (4, "郑州", "河南省", "中原"),
    (5, "上海", "上海市", "华东"), (6, "杭州", "浙江省", "华东"),
    (7, "广州", "广东省", "华南"), (8, "深圳", "广东省", "华南"),
    (9, "成都", "四川省", "西南"), (10, "西安", "陕西省", "西北"),
    (11, "兰州", "甘肃省", "西北"), (12, "海口", "海南省", "华南"),
]
CITY_ID = {n: c for c, n, *_ in CITY_META}
CITY_PROV = {n: p for _c, n, p, _r in CITY_META}
CITY_REG = {n: r for _c, n, _p, r in CITY_META}
LEVEL_LABEL = ["优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"]

DAILY_HEADER = ["date", "city_id", "city_name", "province", "region",
                "pm25", "pm10", "so2", "no2", "co", "o3",
                "aqi", "level", "level_label", "primary_pollutant"]


def fmt(v, field):
    if v is None:
        return ""
    if field == "co":
        return f"{round(float(v), 1):.1f}"
    return str(int(round(float(v))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minhours", type=int, default=12)
    ap.add_argument("--out", default=str(Path(__file__).parent.parent / "dataset"))
    args = ap.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    days = [date(2025, 1, 1) + timedelta(days=i) for i in range(365)]
    rows, dropped, aqi_matrix = [], [], {}
    files_missing = 0

    for d in days:
        f = RAW / f"china_cities_{d.strftime('%Y%m%d')}.csv"
        if not f.exists():
            files_missing += 1
            dropped.append((d, 0))
            continue
        data, hours = parse_day(str(f))
        if hours < args.minhours:
            dropped.append((d, hours))
            continue
        row_aqi = {}
        for city in CITIES:
            rec = data.get(city)
            if not rec:
                continue
            stored = round_for_storage(rec)   # AQI 基于入库精度计算，保证可从 CSV/库内复算
            aqi, label, primary = daily_aqi(stored)
            if aqi is None:
                continue
            level = LEVEL_LABEL.index(label) + 1
            rows.append([
                d.isoformat(), CITY_ID[city], city, CITY_PROV[city], CITY_REG[city],
                fmt(stored.get("pm25"), "pm25"), fmt(stored.get("pm10"), "pm10"),
                fmt(stored.get("so2"), "so2"), fmt(stored.get("no2"), "no2"),
                fmt(stored.get("co"), "co"), fmt(stored.get("o3"), "o3"),
                aqi, level, label, primary or "",
            ])
            row_aqi[city] = aqi
        if row_aqi:
            aqi_matrix[d.isoformat()] = row_aqi

    rows.sort(key=lambda r: (r[0], r[1]))

    def claim(path: Path) -> Path:
        """若目标文件被占用（预览器/杀软锁定），先改名为 .bak 让出文件名；
        仍失败则退到版本化文件名。保证脚本在文件被打开时也能跑出结果。"""
        if not path.exists():
            return path
        bak = path.with_suffix(path.suffix + ".bak")
        try:
            path.rename(bak)
            return path
        except OSError:
            alt = path.with_name(path.stem + "_v2" + path.suffix)
            print(f"提示: {path.name} 被占用，改写到 {alt.name}")
            return alt

    daily_csv = claim(out_dir / "air_quality_2025_daily.csv")
    with open(daily_csv, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(DAILY_HEADER)
        w.writerows(rows)

    matrix_csv = claim(out_dir / "air_quality_2025_aqi_matrix.csv")
    with open(matrix_csv, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["date"] + [n for _c, n, *_ in CITY_META])
        for dstr in sorted(aqi_matrix):
            w.writerow([dstr] + [aqi_matrix[dstr].get(n, "") for _c, n, *_ in CITY_META])

    # ---- 统计与元数据 ----
    per_city = {n: [r for r in rows if r[2] == n] for _c, n, *_ in CITY_META}
    aqi_all = [int(r[11]) for r in rows]
    level_dist = {lbl: sum(1 for r in rows if r[13] == lbl) for lbl in LEVEL_LABEL}
    primary_dist = {}
    for r in rows:
        primary_dist[r[14] or "无"] = primary_dist.get(r[14] or "无", 0) + 1

    lines = []
    lines.append("城市空气质量看板 · 2025 年完整数据集（12 城日粒度）")
    lines.append("")
    lines.append(f"生成时间: {date.today().isoformat()}   生成脚本: db/merge_dataset.py")
    lines.append("数据来源: 中国环境监测总站（全国城市空气质量实时发布平台），经 quotsoft.net 镜像下载")
    lines.append("")
    lines.append("【文件说明】")
    lines.append(f"  air_quality_2025_daily.csv       长表 {len(rows)} 行，一行 = 一城一天")
    lines.append(f"  air_quality_2025_aqi_matrix.csv  宽表 {len(aqi_matrix)} 行，一行 = 一天 × 12 城 AQI")
    lines.append("  编码: UTF-8 with BOM，Excel 双击可直接打开不乱码")
    lines.append("")
    lines.append("【字段口径】（详见 docs/dataset.md）")
    lines.append("  date            监测日期")
    lines.append("  city_id/name    城市编号与名称（1-12）")
    lines.append("  province/region 省份 / 所属地理区")
    lines.append("  pm25/pm10/so2/no2/o3  24h 均值，μg/m³；o3 为 8h 滑动均值当日最大")
    lines.append("  co              24h 均值，mg/m³")
    lines.append("  aqi             日报 AQI，按 HJ 633-2012 日均分段表计算，取六项 IAQI 最大")
    lines.append("  level/level_label  等级 1-6：优/良/轻度污染/中度污染/重度污染/严重污染")
    lines.append("  primary_pollutant  首要污染物（AQI≤50 时为空）")
    lines.append("")
    lines.append("【清洗规则】")
    lines.append(f"  1. 只取 12 个目标城市列")
    lines.append("  2. 只取日报口径类型行（PM2.5_24h/PM10_24h/SO2_24h/NO2_24h/CO_24h/O3_8h_24h），取当日最后一个非空小时")
    lines.append(f"  3. 质量闸门: 原始文件有效小时数 < {args.minhours} 的日期整日丢弃（源头数据残缺，日均值不可靠）")
    lines.append("")
    lines.append(f"【数据完整度】")
    lines.append(f"  原始文件: 365 个，缺失 {files_missing} 个")
    lines.append(f"  丢弃日期: {len(dropped)} 天")
    for d, h in dropped:
        lines.append(f"    - {d}（有效小时 {h}）")
    lines.append(f"  有效天数: {len(aqi_matrix)} 天   总行数: {len(rows)}（理论满额 4,380）")
    lines.append("")
    lines.append("【各城市统计】")
    lines.append(f"  {'城市':<8}{'行数':>6}{'AQI最小':>9}{'AQI最大':>9}{'AQI均值':>10}")
    for _c, n, *_r in CITY_META:
        vals = [int(r[11]) for r in per_city[n]]
        if vals:
            lines.append(f"  {n:<8}{len(vals):>6}{min(vals):>9}{max(vals):>9}{sum(vals)/len(vals):>10.1f}")
    lines.append("")
    lines.append("【AQI 等级分布】")
    for lbl in LEVEL_LABEL:
        lines.append(f"  {lbl}: {level_dist[lbl]} 行")
    lines.append("")
    lines.append("【首要污染物分布】")
    for k in sorted(primary_dist, key=lambda x: -primary_dist[x]):
        lines.append(f"  {k}: {primary_dist[k]} 行")
    lines.append("")
    lines.append(f"【整体 AQI】最小 {min(aqi_all)}，最大 {max(aqi_all)}，均值 {sum(aqi_all)/len(aqi_all):.1f}")
    lines.append("  注: AQI=500 为春季沙尘导致 PM10 超过国标最高分段后按 HJ 633-2012 封顶，属真实事件")

    meta = out_dir / "dataset_meta.txt"
    meta.write_text("\n".join(lines), encoding="utf-8")

    print("\n".join(lines))
    print(f"\n已生成: {daily_csv}")
    print(f"已生成: {matrix_csv}")
    print(f"已生成: {meta}")


if __name__ == "__main__":
    main()
