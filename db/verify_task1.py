# -*- coding: utf-8 -*-
"""
任务 1（数据集定义）验收脚本 —— 独立复算，不复用清洗逻辑，只相信数据本身。

检查项:
  A. 定义文档 dataset.md 是否覆盖必要章节与关键内容
  B. 数据文件存在性与规模
  C. 三块看板图表的支撑性（概览卡 / 趋势图 / 排行榜）
  D. 字段缺失统计（关键字段必须 0 缺失）
  E. AQI 独立复算（脚本内另写一份 HJ 633-2012 分段表，与数据集中的 aqi 逐行比对）
  F. 一致性校验（level 与 aqi 区间、首要污染物规则、主键唯一）
  G. 极端值核查
  H. init.sql 与 CSV 的一致性
  I. 20 行样本草图（12 城最新日 + 北京 8 天趋势）

输出: docs/task1_verification.md
"""
import csv
import re
from pathlib import Path
from collections import Counter, defaultdict

ROOT = Path(__file__).parent.parent


def newest_dataset() -> Path:
    """取 dataset/ 下最新的长表文件（跳过 .bak）。

    背景: 已生成的文件可能被预览器/其他进程占用导致无法覆盖写入，
    merge_dataset.py 会退让为 _v2 文件名；此处自动取最新的一份，避免误读旧文件。
    """
    cands = [p for p in (ROOT / "dataset").glob("air_quality_2025_daily*.csv")
             if not p.name.endswith(".bak")]
    if not cands:
        raise FileNotFoundError("dataset/ 下未找到长表数据集文件")
    return max(cands, key=lambda p: p.stat().st_mtime)


DATASET = newest_dataset()
DOC = ROOT / "docs" / "dataset.md"
SQL = ROOT / "db" / "init.sql"
REPORT = ROOT / "docs" / "task1_verification.md"

# ---- HJ 633-2012 日均分段表（此处独立再写一份，用于复算校验）----
BP = {
    "pm25": [(0, 0), (35, 50), (75, 100), (115, 150), (150, 200), (250, 300), (350, 400), (500, 500)],
    "pm10": [(0, 0), (50, 50), (150, 100), (250, 150), (350, 200), (420, 300), (500, 400), (600, 500)],
    "so2":  [(0, 0), (50, 50), (150, 100), (475, 150), (800, 200), (1600, 300), (2100, 400), (2620, 500)],
    "no2":  [(0, 0), (40, 50), (80, 100), (180, 150), (280, 200), (565, 300), (750, 400), (940, 500)],
    "co":   [(0, 0), (2, 50), (4, 100), (14, 150), (24, 200), (36, 300), (48, 400), (60, 500)],
    "o3":   [(0, 0), (100, 50), (160, 100), (215, 150), (265, 200), (800, 300)],
}
LABEL2IDX = {"pm25": "PM2.5", "pm10": "PM10", "so2": "SO2", "no2": "NO2", "co": "CO", "o3": "O3"}
LEVELS = [(50, 1), (100, 2), (150, 3), (200, 4), (300, 5)]
LEVEL_LABEL = ["优", "良", "轻度污染", "中度污染", "重度污染", "严重污染"]


def iaqi(field, c):
    bps = BP[field]
    if c <= 0:
        return 0.0
    if c >= bps[-1][0]:
        return 500.0
    for (cl, il), (ch, ih) in zip(bps, bps[1:]):
        if cl <= c < ch:
            return (ih - il) / (ch - cl) * (c - cl) + il
    return 500.0


def level_of(aqi):
    for hi, lv in LEVELS:
        if aqi <= hi:
            return lv
    return 6


def num(v):
    return None if v == "" else float(v)


def check(ok):
    return "PASS" if ok else "FAIL"


def main():
    R = []          # 报告行
    results = []    # (检查项, 结论, 说明)

    def add(name, ok, note):
        results.append((name, check(ok), note))

    # ---------- A. 定义文档 ----------
    doc = DOC.read_text(encoding="utf-8") if DOC.exists() else ""
    need_sections = ["## 1.", "## 2.", "## 3.", "## 4.", "## 5.", "## 6.", "## 7.", "## 8.", "## 9.", "## 10."]
    missing_sec = [s for s in need_sections if s not in doc]
    add("A1 dataset.md 十节齐全", not missing_sec, f"缺失章节: {missing_sec or '无'}")
    city_kw = ["北京", "石家庄", "哈尔滨", "郑州", "上海", "杭州", "广州", "深圳", "成都", "西安", "兰州", "海口"]
    miss_city = [c for c in city_kw if c not in doc]
    add("A2 12 城清单完整", not miss_city, f"缺失城市: {miss_city or '无'}")
    add("A3 含 IAQI 分段表", "HJ 633-2012" in doc and "分段表" in doc, "文档引用国标并含分段表")
    add("A4 含等级配色表", "#00E400" in doc and "#7E0023" in doc, "六档配色已定义")
    add("A5 含接口 JSON 契约", "/api/cities" in doc and "/api/aqi/ranking" in doc, "前后端契约已固定")

    # ---------- B. 数据文件 ----------
    if not DATASET.exists():
        print("数据文件不存在，终止")
        return
    with open(DATASET, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    add("B1 长表文件存在", True, str(DATASET))
    add("B2 行数符合预期(4,332)", len(rows) == 4332, f"实际 {len(rows)} 行")
    header = list(rows[0].keys())
    expect_cols = ["date", "city_id", "city_name", "province", "region", "pm25", "pm10",
                   "so2", "no2", "co", "o3", "aqi", "level", "level_label", "primary_pollutant"]
    miss_col = [c for c in expect_cols if c not in header]
    add("B3 15 个字段齐全", not miss_col, f"缺失字段: {miss_col or '无'}")

    # ---------- D. 缺失统计 ----------
    nulls = {c: sum(1 for r in rows if r[c] == "") for c in header}
    critical = ["date", "city_id", "city_name", "aqi", "level", "level_label"]
    crit_null = {c: nulls[c] for c in critical if nulls[c]}
    add("D1 关键字段 0 缺失", not crit_null, f"date/city_id/city_name/aqi/level 空值: {crit_null or '全为 0'}")
    pol_null = {c: nulls[c] for c in ["pm25", "pm10", "so2", "no2", "co", "o3"] if nulls[c]}
    add("D2 污染物字段缺失", not pol_null, f"六项污染物空值: {pol_null or '全为 0'}（空值可参与计算，非阻断）")

    # ---------- E. AQI 独立复算 ----------
    mismatch, mismatch_samples = 0, []
    primary_bad, primary_samples = 0, []
    level_bad = 0
    for r in rows:
        vals = {k: num(r[k]) for k in ["pm25", "pm10", "so2", "no2", "co", "o3"]}
        ia = {k: iaqi(k, v) for k, v in vals.items() if v is not None}
        if not ia:
            continue
        calc = int(round(max(ia.values())))
        stored = int(r["aqi"])
        if calc != stored:
            mismatch += 1
            if len(mismatch_samples) < 5:
                mismatch_samples.append(f"{r['date']} {r['city_name']} 存储={stored} 复算={calc}")
        # level 一致性
        if level_of(stored) != int(r["level"]):
            level_bad += 1
        # 首要污染物规则
        if stored > 50:
            top = max(ia.items(), key=lambda x: x[1])
            expect = LABEL2IDX[top[0]] if top[1] > 50 else None
            if expect != r["primary_pollutant"]:
                primary_bad += 1
                if len(primary_samples) < 5:
                    primary_samples.append(f"{r['date']} {r['city_name']} 存储={r['primary_pollutant']} 复算={expect}")
        else:
            if r["primary_pollutant"] != "":
                primary_bad += 1
                if len(primary_samples) < 5:
                    primary_samples.append(f"{r['date']} {r['city_name']} AQI≤50 却有首要污染物={r['primary_pollutant']}")
    add("E1 AQI 独立复算一致", mismatch == 0, f"不一致 {mismatch} 行 {mismatch_samples}")
    add("E2 level 与 AQI 区间一致", level_bad == 0, f"不一致 {level_bad} 行")
    add("E3 首要污染物规则一致", primary_bad == 0, f"不一致 {primary_bad} 行 {primary_samples}")

    # ---------- F. 唯一性 ----------
    keys = [(r["city_id"], r["date"]) for r in rows]
    dup = len(keys) - len(set(keys))
    add("F1 (city_id, date) 唯一", dup == 0, f"重复 {dup} 行")

    # ---------- C. 三块图表支撑性 ----------
    by_city = defaultdict(list)
    for r in rows:
        by_city[int(r["city_id"])].append(r)
    for v in by_city.values():
        v.sort(key=lambda r: r["date"])
    max_date = max(r["date"] for r in rows)

    latest = [r for r in rows if r["date"] == max_date]
    add("C1 概览卡：最新日 12 城均有数据", len(latest) == 12,
        f"最新日 {max_date}，{len(latest)} 城有数据；样例 {latest[0]['city_name']} "
        f"AQI={latest[0]['aqi']} {latest[0]['level_label']} 首要={latest[0]['primary_pollutant'] or '无'}")

    bj = by_city[1]
    n7 = [r for r in bj if r["date"] >= "2025-12-25"]
    n30 = [r for r in bj if r["date"] >= "2025-12-02"]
    n365 = bj
    add("C2 趋势图：近 7 / 30 / 365 天均可取数",
        len(n7) >= 6 and len(n30) >= 25 and len(n365) >= 355,
        f"北京 近7天 {len(n7)} 点、近30天 {len(n30)} 点、全年 {len(n365)} 点")

    ranked = sorted(latest, key=lambda r: int(r["aqi"]))
    lvl_cover = {int(r["level"]) for r in rows}
    add("C3 排行榜：同日 12 城快照可排序", len(ranked) == 12,
        f"最差 {ranked[-1]['city_name']} AQI={ranked[-1]['aqi']}，最优 {ranked[0]['city_name']} AQI={ranked[0]['aqi']}")
    add("C4 六档等级全覆盖（配色可全渲染）", len(lvl_cover) == 6, f"覆盖等级: {sorted(lvl_cover)}")

    # ---------- G. 极端值 ----------
    extreme = [r for r in rows if int(r["aqi"]) >= 300]
    ext_desc = "; ".join(f"{r['date']} {r['city_name']} AQI={r['aqi']}({r['primary_pollutant']})" for r in extreme[:4])
    add("G1 极端值可解释", all(int(r["aqi"]) <= 500 for r in rows),
        f"AQI≥300 共 {len(extreme)} 行，样例: {ext_desc}")

    # ---------- H. init.sql 一致性 ----------
    if SQL.exists():
        text = SQL.read_text(encoding="utf-8")
        pat = re.compile(r"^\s*\((\d+), '(\d{4}-\d{2}-\d{2})', ([^,]+), ([^,]+), ([^,]+), "
                         r"([^,]+), ([^,]+), ([^,]+), (\d+), ([^,]+), (\d+)\)\s*[,;]\s*$")
        sql_rows = [m.groups() for m in (pat.match(l) for l in text.splitlines()) if m]
        add("H1 init.sql 行数与 CSV 一致", len(sql_rows) == len(rows), f"SQL {len(sql_rows)} 行 vs CSV {len(rows)} 行")
        sql_keys = {(int(g[0]), g[1], int(g[8])) for g in sql_rows}
        csv_keys = {(int(r["city_id"]), r["date"], int(r["aqi"])) for r in rows}
        diff = len(sql_keys ^ csv_keys)
        add("H2 SQL 与 CSV 内容一致", diff == 0, f"差异行数 {diff}")
    else:
        add("H1 init.sql 存在", False, "未找到 init.sql")

    # ---------- I. 20 行样本草图 ----------
    sample = list(ranked) + [r for r in bj if r["date"] >= "2025-02-26"][:8]
    add("I1 20 行样本可支撑三图草图", len(sample) >= 20, f"样本 {len(sample)} 行（12 城快照 + 北京 8 天趋势）")

    # ---------- 生成报告 ----------
    total = len(results)
    passed = sum(1 for r in results if r[1] == "PASS")
    R.append("# 任务 1（数据集定义）验收报告")
    R.append("")
    R.append(f"生成时间: 验收脚本 `db/verify_task1.py` 独立复算（不复用清洗逻辑）")
    R.append(f"验收对象: `docs/dataset.md`、`{DATASET.name}`、`db/init.sql`")
    R.append("")
    R.append(f"**结论: {passed}/{total} 项通过{'（全部通过）' if passed == total else '（存在未通过项，见下表）'}**")
    R.append("")
    R.append("| # | 检查项 | 结果 | 说明 |")
    R.append("|---|---|---|---|")
    for i, (name, res, note) in enumerate(results, 1):
        flag = "✅" if res == "PASS" else "❌"
        R.append(f"| {i} | {name} | {flag} {res} | {note} |")
    R.append("")
    R.append("## 数据概况")
    R.append("")
    R.append(f"- 总行数: {len(rows)}（12 城 × 361 天；理论满额 4,380，4 天因源头数据残缺丢弃）")
    R.append(f"- 日期范围: {min(r['date'] for r in rows)} ~ {max_date}")
    R.append(f"- 各城市行数: {dict(Counter(int(r['city_id']) for r in rows))}（应各为 361）")
    R.append(f"- AQI 区间: {min(int(r['aqi']) for r in rows)} ~ {max(int(r['aqi']) for r in rows)}，"
             f"均值 {sum(int(r['aqi']) for r in rows)/len(rows):.1f}")
    R.append(f"- 等级分布: {dict(Counter(r['level_label'] for r in rows))}")
    R.append(f"- 首要污染物分布: {dict(Counter(r['primary_pollutant'] or '无' for r in rows))}")
    R.append(f"- 各字段空值数: { {k: v for k, v in nulls.items() if v} or '全部字段 0 空值'}")
    R.append("")
    R.append("## 20 行样本（草图校验）")
    R.append("")
    R.append("### 排行榜快照（" + max_date + "，按 AQI 升序）")
    R.append("")
    R.append("| 城市 | AQI | 等级 | 首要污染物 |")
    R.append("|---|---|---|---|")
    for r in ranked:
        R.append(f"| {r['city_name']} | {r['aqi']} | {r['level_label']} | {r['primary_pollutant'] or '无'} |")
    R.append("")
    R.append("### 趋势片段（北京 2025-02-26 起 8 天）")
    R.append("")
    R.append("| 日期 | AQI | 等级 | 首要污染物 |")
    R.append("|---|---|---|---|")
    for r in [x for x in bj if x["date"] >= "2025-02-26"][:8]:
        R.append(f"| {r['date']} | {r['aqi']} | {r['level_label']} | {r['primary_pollutant'] or '无'} |")

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(R), encoding="utf-8")
    print("\n".join(R[:40]))
    print(f"\n报告已生成: {REPORT}")
    print(f"验收结论: {passed}/{total} 通过")


if __name__ == "__main__":
    main()
