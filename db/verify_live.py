# -*- coding: utf-8 -*-
"""任务 3~7 线上行为复验：真实 HTTP 请求，含 CORS 反向用例。"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
FRONT = "http://127.0.0.1:5173"


def call(method, url, headers=None):
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:
        return None, {}, f"{type(e).__name__}: {e}"


print("=" * 64)
print("V1  前端开发服务器（任务 4）")
print("=" * 64)
st, _, body = call("GET", FRONT + "/")
print(f"  GET /  -> {st}")
print(f"  含挂载点 <div id=\"app\">: {'<div id=\"app\">' in body}")
print(f"  含入口脚本 /src/main.js : {'/src/main.js' in body}")
print(f"  标题: {body.split('<title>')[1].split('</title>')[0] if '<title>' in body else 'N/A'}")

print()
print("=" * 64)
print("V2  后端启动 + 全局异常处理（任务 3 + 任务 6）")
print("=" * 64)
st, hdrs, body = call("GET", BASE + "/api/__probe")
print(f"  GET /api/__probe -> {st}")
print(f"  Content-Type: {hdrs.get('Content-Type')}")
print(f"  Body: {body}")
try:
    obj = json.loads(body)
    print(f"  字段集合: {sorted(obj.keys())}")
    print(f"  === 契约 {{code,msg,data}} 严格一致（无 success 冗余字段）: {sorted(obj.keys()) == ['code', 'msg']}")
except Exception as e:
    print("  JSON 解析失败:", e)

print()
print("=" * 64)
print("V3  CORS 正向用例（任务 7）")
print("=" * 64)
st, hdrs, _ = call("OPTIONS", BASE + "/api/cities", {
    "Origin": "http://localhost:5173",
    "Access-Control-Request-Method": "GET",
})
print(f"  OPTIONS /api/cities  Origin=localhost:5173 -> {st}")
print(f"  Access-Control-Allow-Origin : {hdrs.get('Access-Control-Allow-Origin')}")
print(f"  Access-Control-Allow-Methods: {hdrs.get('Access-Control-Allow-Methods')}")
print(f"  Access-Control-Max-Age      : {hdrs.get('Access-Control-Max-Age')}")

print()
print("=" * 64)
print("V4  CORS 反向用例（未授权来源不应拿到放行头）")
print("=" * 64)
st, hdrs, _ = call("OPTIONS", BASE + "/api/cities", {
    "Origin": "http://evil.example.com",
    "Access-Control-Request-Method": "GET",
})
acao = hdrs.get("Access-Control-Allow-Origin")
print(f"  OPTIONS /api/cities  Origin=evil.example.com -> {st}")
print(f"  Access-Control-Allow-Origin : {acao}")
print(f"  === 未给未授权来源放行: {acao is None}")

print()
print("=" * 64)
print("V5  端口核对（任务 3：应为 8080，而非被 SERVER__PORT 劫持的 57544）")
print("=" * 64)
print(f"  8080 可访问: {st is not None}")

print()
print("=" * 64)
print("V6  前端 /api 代理链路（前端 -> 后端）")
print("=" * 64)
st, hdrs, body = call("GET", FRONT + "/api/__probe")
print(f"  GET 5173/api/__probe -> {st}")
print(f"  Body: {body[:120]}")
print(f"  === 代理已生效（返回后端统一错误体）: {st == 404 and '接口不存在' in body}")
