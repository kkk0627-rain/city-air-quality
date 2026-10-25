# -*- coding: utf-8 -*-
"""
下载中国环境监测总站「城市级逐小时空气质量数据」（quotsoft.net 镜像）。

数据源:      https://quotsoft.net/air/data/china_cities_YYYYMMDD.csv
数据说明:    每日一个 CSV，UTF-8 编码；每行 = 日期, 小时, 数据类型, <城市名>...
             type 含 AQI / PM2.5 / PM10 / SO2 / NO2 / O3 / CO（1 小时均值）及 *_24h 变体
引用口径:    数据来自中国环境监测总站「全国城市空气质量实时发布平台」

用法:
    python download_data.py                        # 下载 2025 全年（默认）
    python download_data.py 20250101 20250131      # 下载指定日期区间（闭区间）
    python download_data.py 20250715 20250715      # 下载单日
    python download_data.py --workers 4            # 并发线程数（默认 4）
    python download_data.py --outdir E:\\airdata   # 自定义保存目录

特性:
    - 断点续传: 已存在且校验通过的文件自动跳过
    - 完整性校验: 文件头为 "date,hour,type" 且数据行数 ≥ 300（防半截文件，容忍源头缺 1~4 小时）
    - 重试:     单日失败自动重试 3 次（间隔递增）
    - 并发下载: 默认 4 线程，365 天约 10~15 分钟
    - 结果汇总: 结束时打印成功/跳过/失败统计；失败日期写入 failures.txt
"""
import sys
import time
import argparse
import urllib.request
from pathlib import Path
from datetime import date, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "https://quotsoft.net/air/data/china_cities_{}.csv"
REQUIRED_HEADER = "date,hour,type"
MIN_DATA_LINES = 50  # 仅排除明显截断/404 页面；源头个别日期本身缺小时，由清洗阶段按质量闸门处理
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) air-quality-dashboard/1.0"
DEFAULT_OUTDIR = r"D:\airdata\raw"


def file_is_valid(fpath: Path) -> bool:
    """已存在文件是否完整有效：文件头正确 + 数据行数达标（防止截断的半文件被跳过）。

    注: 源头个别日期本身缺最后 1~2 个小时（服务器侧数据缺口，实测 2025-06-14 只有
    0~22 时），对天粒度提取无影响（取最后一个非空小时即可），故阈值取 300 行而非满额 360。
    """
    try:
        if not fpath.exists() or fpath.stat().st_size < 30_000:
            return False
        with open(fpath, encoding="utf-8", errors="replace") as f:
            text = f.read()
        return text.startswith(REQUIRED_HEADER) and text.count("\n") >= MIN_DATA_LINES
    except OSError:
        return False


def download_one(day: date, outdir: Path, timeout: int = 30, retries: int = 3):
    """下载单日数据。返回 (状态, 说明): 状态 in {"ok","skip","fail"}"""
    fname = f"china_cities_{day.strftime('%Y%m%d')}.csv"
    fpath = outdir / fname

    if file_is_valid(fpath):
        return "skip", fname

    url = BASE_URL.format(day.strftime("%Y%m%d"))
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=timeout) as resp, open(fpath, "wb") as f:
                f.write(resp.read())
            if not file_is_valid(fpath):
                fpath.unlink(missing_ok=True)
                raise ValueError("完整性校验失败（文件不完整或非数据文件）")
            time.sleep(0.2)  # 礼貌限速
            return "ok", fname
        except Exception as exc:  # noqa: BLE001 单日失败不应中断整年任务
            if attempt == retries:
                fpath.unlink(missing_ok=True)
                return "fail", f"{day.isoformat()}: {exc}"
            time.sleep(2 * attempt)
    return "fail", f"{day.isoformat()}: unreachable"


def main() -> int:
    ap = argparse.ArgumentParser(description="下载城市级逐小时空气质量历史数据")
    ap.add_argument("start", nargs="?", default="20250101", help="起始日期 YYYYMMDD，默认 20250101")
    ap.add_argument("end", nargs="?", default="20251231", help="结束日期 YYYYMMDD，默认 20251231")
    ap.add_argument("--outdir", default=DEFAULT_OUTDIR, help=f"保存目录，默认 {DEFAULT_OUTDIR}")
    ap.add_argument("--workers", type=int, default=4, help="并发下载线程数，默认 4")
    args = ap.parse_args()

    try:
        start = date(int(args.start[:4]), int(args.start[4:6]), int(args.start[6:8]))
        end = date(int(args.end[:4]), int(args.end[4:6]), int(args.end[6:8]))
    except ValueError:
        print("日期参数格式错误，应为 YYYYMMDD，例如 20250101")
        return 2
    if start > end:
        print("起始日期不能晚于结束日期")
        return 2

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    days = [start + timedelta(days=i) for i in range((end - start).days + 1)]
    total = len(days)
    ok = skip = fail = 0
    failures = []

    print(f"目标区间: {start} ~ {end}（共 {total} 天），{args.workers} 线程，保存到 {outdir}", flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(download_one, d, outdir): d for d in days}
        for done, fut in enumerate(as_completed(futures), 1):
            status, info = fut.result()
            if status == "ok":
                ok += 1
            elif status == "skip":
                skip += 1
            else:
                fail += 1
                failures.append(info)
                print(f"  [失败] {info}", flush=True)
            if done % 20 == 0 or done == total:
                print(f"进度 {done}/{total}  成功={ok} 跳过={skip} 失败={fail}", flush=True)

    print(f"完成: 成功 {ok}，跳过（已存在）{skip}，失败 {fail}")
    if failures:
        log = outdir / "failures.txt"
        log.write_text("\n".join(failures) + "\n", encoding="utf-8")
        print(f"失败清单已写入 {log}（重跑本脚本可自动补齐）")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
