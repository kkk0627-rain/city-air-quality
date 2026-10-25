# -*- coding: utf-8 -*-
"""任务 3~7 复验：清点交付文件 + 核对数据库基线。"""
import subprocess
from pathlib import Path

ROOT = Path("C:/Users/Lenovo/WorkBuddy/web城市空气质量看板")

DELIVERABLES = {
    "任务3 后端骨架": [
        "backend/pom.xml",
        "backend/src/main/resources/application.yml",
        "backend/src/main/java/com/aqi/dashboard/AqiDashboardApplication.java",
    ],
    "任务4 前端骨架": [
        "frontend/package.json",
        "frontend/vite.config.js",
        "frontend/index.html",
        "frontend/src/main.js",
        "frontend/src/App.vue",
    ],
    "任务5 统一返回体": [
        "backend/src/main/java/com/aqi/dashboard/common/Result.java",
    ],
    "任务6 全局异常处理": [
        "backend/src/main/java/com/aqi/dashboard/common/GlobalExceptionHandler.java",
    ],
    "任务7 跨域配置": [
        "backend/src/main/java/com/aqi/dashboard/config/CorsConfig.java",
    ],
    "验证件（测试）": [
        "backend/src/test/java/com/aqi/dashboard/common/ResultTest.java",
        "backend/src/test/java/com/aqi/dashboard/common/GlobalExceptionHandlerTest.java",
        "docs/task3_7_verification.md",
    ],
}

print("=" * 62)
print("一、交付文件清点")
print("=" * 62)
missing = []
for group, files in DELIVERABLES.items():
    print(f"\n[{group}]")
    for rel in files:
        p = ROOT / rel
        if p.exists():
            n = len(p.read_text(encoding="utf-8", errors="replace").splitlines())
            print(f"  OK   {rel}  ({p.stat().st_size:,} B, {n} 行)")
        else:
            print(f"  MISS {rel}")
            missing.append(rel)

print(f"\n合计缺失: {len(missing)}")

print()
print("=" * 62)
print("二、数据库基线（MySQL 8.0 / aqi_dashboard）")
print("=" * 62)
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
sql = (
    "SELECT COUNT(*) FROM air_quality; "
    "SELECT COUNT(*) FROM city; "
    "SELECT COUNT(DISTINCT city_id) FROM air_quality; "
    "SELECT MIN(monitor_date), MAX(monitor_date) FROM air_quality; "
    "SELECT level, COUNT(*) FROM air_quality GROUP BY level ORDER BY level;"
)
try:
    r = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "--table", "-e", sql, "aqi_dashboard"],
        capture_output=True, timeout=90,
    )
    out = r.stdout.decode("utf-8", errors="replace")
    err = r.stderr.decode("utf-8", errors="replace")
    for line in out.splitlines():
        if "Warning" not in line:
            print("  " + line)
    if err.strip():
        print("  [stderr]", err.strip()[:300])
except Exception as e:
    print("  数据库查询失败:", e)
