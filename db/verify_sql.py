# -*- coding: utf-8 -*-
"""回读 init.sql 做结构与统计校验：语句完整性、每城统计、异常值定位、趋势样本抽取。"""
import re
from pathlib import Path
from collections import defaultdict

SQL = Path(__file__).parent / "init.sql"
CITY_NAME = {i: n for i, n in [
    (1, "北京"), (2, "石家庄"), (3, "哈尔滨"), (4, "郑州"), (5, "上海"), (6, "杭州"),
    (7, "广州"), (8, "深圳"), (9, "成都"), (10, "西安"), (11, "兰州"), (12, "海口")]}


def main():
    text = SQL.read_text(encoding="utf-8")
    # 抽取 air_quality 的值行: (city_id, 'date', pm25, pm10, so2, no2, co, o3, aqi, 'prim', level)
    pattern = re.compile(r"^\s*\((\d+), '(\d{4}-\d{2}-\d{2})', ([^,]+), ([^,]+), ([^,]+), "
                         r"([^,]+), ([^,]+), ([^,]+), (\d+), ([^,]+), (\d+)\)\s*[,;]\s*$")
    rows, bad = [], []
    for line in text.splitlines():
        m = pattern.match(line)
        if m:
            rows.append(m.groups())
        elif line.strip().startswith("("):
            bad.append(line.strip())

    print(f"解析到数据行: {len(rows)}；无法解析的行: {len(bad)}")
    for b in bad[:5]:
        print(f"  异常行: {b[:100]}")

    per_city = defaultdict(list)
    for cid, dstr, *_rest in rows:
        per_city[int(cid)].append(rows[rows.index((cid, dstr) + _rest)] if False else None)

    # 重新按城市分组统计
    stats = defaultdict(list)
    for r in rows:
        stats[int(r[0])].append((r[1], int(r[8]), int(r[10]), r[9].strip("'") if r[9] != "NULL" else None))

    print("\n各城市 AQI 统计:")
    print(f"{'城市':<8}{'行数':>6}{'最小':>6}{'最大':>6}{'均值':>8}")
    for cid in sorted(stats):
        vals = [v[1] for v in stats[cid]]
        print(f"{CITY_NAME[cid]:<8}{len(vals):>6}{min(vals):>6}{max(vals):>6}{sum(vals)/len(vals):>8.1f}")

    print("\nAQI ≥ 300 的重度/严重污染行（异常核查）:")
    extreme = [(CITY_NAME[int(r[0])], r[1], r[8], r[9].strip("'"), r[2]) for r in rows if int(r[8]) >= 300]
    for e in sorted(extreme, key=lambda x: -int(x[2]))[:15]:
        print(f"  {e[0]} {e[1]}  AQI={e[2]}  首要={e[3]}  PM2.5={e[4]}")
    print(f"  …… 共 {len(extreme)} 行 ≥300")

    # 北京最差一周（供文档趋势样本）
    bj = sorted(stats[1], key=lambda x: -x[1])
    worst_day = bj[0][0]
    print(f"\n北京全年最差日: {worst_day} AQI={bj[0][1]}")
    idx = [i for i, v in enumerate(sorted(stats[1], key=lambda x: x[0])) if v[0] == worst_day][0]
    seq = sorted(stats[1], key=lambda x: x[0])
    window = seq[max(0, idx - 3): idx + 4]
    print("该日前后各 3 天（趋势样本）:")
    for d, aqi, lvl, prim in window:
        print(f"  {d}  AQI={aqi}  等级={lvl}  首要={prim or '无'}")

    # 文档用的 7 天样例：北京 1 月 8-14
    jan = [v for v in seq if "2025-01-08" <= v[0] <= "2025-01-14"]
    print("\n北京 2025-01-08 ~ 01-14:")
    for d, aqi, lvl, prim in jan:
        print(f"  {d}  AQI={aqi}  等级={lvl}  首要={prim or '无'}")


if __name__ == "__main__":
    main()
