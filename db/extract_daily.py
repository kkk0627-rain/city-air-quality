# -*- coding: utf-8 -*-
"""
从 quotsoft.net 的单日 CSV 中提取 12 个城市的天粒度数据（HJ 633-2012 日报口径）。

方法:
  每日文件的 23 时行里, *_24h 类型即官方日报使用的统计量:
    PM2.5_24h  = PM2.5 的 24 小时均值
    PM10_24h   = PM10  的 24 小时均值
    SO2_24h    = SO2   的 24 小时均值
    NO2_24h    = NO2   的 24 小时均值
    CO_24h     = CO    的 24 小时均值
    O3_8h_24h  = O3 8小时滑动均值的 24 小时最大值
  用上述浓度按 HJ 633-2012 的"日均值分段表"计算 IAQI → 取最大得日报 AQI、
  等级和首要污染物。若 23 时缺值, 回退到当日该类型最后一个非空小时。

用法: python extract_daily.py <csv路径>   （打印 12 城当日日报表）
"""
import sys
import csv
from pathlib import Path

CITIES = ["北京", "石家庄", "哈尔滨", "郑州", "上海", "杭州",
          "广州", "深圳", "成都", "西安", "兰州", "海口"]

# 源类型 -> 字段（日报口径统计量）
TYPE_MAP = {
    "PM2.5_24h": "pm25",
    "PM10_24h": "pm10",
    "SO2_24h": "so2",
    "NO2_24h": "no2",
    "CO_24h": "co",
    "O3_8h_24h": "o3",
}

# HJ 633-2012 日均浓度分段表: (浓度上限, IAQI) 且首项为 (0, 0)
# PM2.5/PM10/SO2/NO2 单位 μg/m³, CO 单位 mg/m³, O3 取 8h 滑动均值最大
BP = {
    "pm25": [(0, 0), (35, 50), (75, 100), (115, 150), (150, 200), (250, 300), (350, 400), (500, 500)],
    "pm10": [(0, 0), (50, 50), (150, 100), (250, 150), (350, 200), (420, 300), (500, 400), (600, 500)],
    "so2":  [(0, 0), (50, 50), (150, 100), (475, 150), (800, 200), (1600, 300), (2100, 400), (2620, 500)],
    "no2":  [(0, 0), (40, 50), (80, 100), (180, 150), (280, 200), (565, 300), (750, 400), (940, 500)],
    "co":   [(0, 0), (2, 50), (4, 100), (14, 150), (24, 200), (36, 300), (48, 400), (60, 500)],
    "o3":   [(0, 0), (100, 50), (160, 100), (215, 150), (265, 200), (800, 300)],
}

LEVEL_LABEL = ["优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"]


def iaqi(field: str, c: float) -> float:
    """分段线性插值计算单项 IAQI（超出最高分段按 500 封顶）。"""
    bps = BP[field]
    if c <= 0:
        return 0.0
    if c >= bps[-1][0]:
        return 500.0
    for (cl, il), (ch, ih) in zip(bps, bps[1:]):
        if cl <= c < ch:
            return (ih - il) / (ch - cl) * (c - cl) + il
    return 500.0


def extract_daily(fpath: str):
    """解析单日 CSV，返回 {城市: {字段: 浓度}}（日报口径）。"""
    return parse_day(fpath)[0]


def parse_day(fpath: str):
    """解析单日 CSV。返回 (日报数据, 有效小时数)。

    日报数据: {城市: {字段: 浓度}}，浓度为当日该类型最后一个非空小时的值。
    有效小时数: 该文件中出现了多少个不同的小时（源头偶发缺小时，用于质量闸门）。
    """
    with open(fpath, encoding="utf-8") as f:
        rows = list(csv.reader(f))
    header = rows[0]
    city_idx = {c: header.index(c) for c in CITIES if c in header}

    # last_nonempty[type][city] = 该日该类型最后一个非空小时的值
    last_nonempty = {t: {} for t in TYPE_MAP}
    hours = set()
    for r in rows[1:]:
        if len(r) < 3:
            continue
        hours.add(r[1])
        typ = r[2]
        if typ not in TYPE_MAP:
            continue
        for city, idx in city_idx.items():
            v = r[idx].strip() if idx < len(r) else ""
            if v not in ("", "-"):
                try:
                    last_nonempty[typ][city] = float(v)
                except ValueError:
                    pass

    result = {}
    for city in CITIES:
        rec = {}
        for typ, field in TYPE_MAP.items():
            if city in last_nonempty.get(typ, {}):
                rec[field] = last_nonempty[typ][city]
        if rec:
            result[city] = rec
    return result, len(hours)


def round_for_storage(rec: dict) -> dict:
    """按入库精度对浓度取整（pm25/pm10/so2/no2/o3 取整, co 保留 1 位小数）。

    AQI 一律以"取整后的浓度"计算，保证任何人从数据库/CSV 复算都能得到完全一致的 AQI
    （否则会有 ±1 的偏差，见 db/verify_task1.py 的 E1 检查）。
    """
    out = {}
    for field, v in rec.items():
        out[field] = int(round(v)) if field != "co" else round(float(v), 1)
    return out


def daily_aqi(rec: dict):
    """由日报口径浓度计算 (AQI, 等级标签, 首要污染物)。"""
    iaqis = {}
    for field, c in rec.items():
        iaqis[field] = iaqi(field, c)
    if not iaqis:
        return None, None, None
    aqi = max(iaqis.values())
    aqi = int(round(aqi))
    level = 1 if aqi <= 50 else 2 if aqi <= 100 else 3 if aqi <= 150 \
        else 4 if aqi <= 200 else 5 if aqi <= 300 else 6
    primary = None
    if aqi > 50:
        cands = [(v, k) for k, v in iaqis.items() if v > 50]
        if cands:
            primary = max(cands)[1]
    label_map = {"pm25": "PM2.5", "pm10": "PM10", "so2": "SO2", "no2": "NO2", "co": "CO", "o3": "O3"}
    return aqi, LEVEL_LABEL[level - 1], (label_map[primary] if primary else None)


def main():
    fpath = sys.argv[1]
    data = extract_daily(fpath)
    day = Path(fpath).stem.replace("china_cities_", "")
    print(f"=== {day} 12 城日报（提取自 24h 均值行，HJ 633-2012 计算） ===")
    print(f"{'城市':<6}{'AQI':>5}  {'等级':<6}{'首要污染物':<10}{'PM2.5':>7}{'PM10':>7}{'O3_8h':>7}")
    table = []
    for city, rec in data.items():
        aqi, label, primary = daily_aqi(rec)
        table.append((aqi, city, label, primary, rec))
    for aqi, city, label, primary, rec in sorted(table):
        pm25 = rec.get("pm25")
        pm10 = rec.get("pm10")
        o3 = rec.get("o3")
        print(f"{city:<6}{aqi:>5}  {label:<6}{(primary or '-'):<10}"
              f"{pm25 if pm25 is not None else '-':>7}"
              f"{pm10 if pm10 is not None else '-':>7}"
              f"{o3 if o3 is not None else '-':>7}")
    print(f"共 {len(data)}/12 城，总行数（天粒度单日）= {len(data)}")


if __name__ == "__main__":
    main()
