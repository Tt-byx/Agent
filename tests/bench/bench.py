"""
压测脚本 —— 统计 QPS / P50 / P99 / 错误率

用法示例：
  python bench.py --url http://localhost:10086/api/chat \
      --concurrency 10,50,100 --duration 20 --warmup 3

设计要点：
  1. **预热阶段单独计时排除**，避免首次建连接、JIT、缓存冷启动污染数据
  2. 每个并发档跑独立的 ThreadPool，互不干扰
  3. 分位数用「最近延迟排序后取下标」，简单够用（样本量几万以内）
  4. 支持 Header 传 token，方便带身份压测
"""
import argparse
import json
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    return sorted_values[f] + (k - f) * (sorted_values[c] - sorted_values[f])


def worker(session, url, payload, headers, stop_at, results):
    """一个 worker：持续发请求直到 stop_at"""
    while time.perf_counter() < stop_at:
        t0 = time.perf_counter()
        try:
            r = session.post(url, json=payload, headers=headers, timeout=60)
            cost = time.perf_counter() - t0
            try:
                code = r.json().get("code")
            except Exception:
                code = None
            results.append((cost, r.status_code, code, None))
        except Exception as e:
            cost = time.perf_counter() - t0
            results.append((cost, -1, None, type(e).__name__))


def run_once(url, payload, headers, concurrency, duration, warmup):
    """跑一轮：先预热，再正式计时"""
    # 预热：让连接池、JIT、语义缓存都热起来
    with requests.Session() as s:
        s.trust_env = False
        for _ in range(max(5, concurrency // 2)):
            try:
                s.post(url, json=payload, headers=headers, timeout=60)
            except Exception:
                pass
    if warmup > 0:
        time.sleep(warmup)

    results = []
    t_start = time.perf_counter()
    stop_at = t_start + duration

    # 每个 worker 独占一个 Session —— requests.Session 不是线程安全的，
    # 多线程共用会出现连接复用错乱、假死等问题
    sessions = [make_session() for _ in range(concurrency)]
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        futures = [
            ex.submit(worker, s, url, payload, headers, stop_at, results)
            for s in sessions
        ]
        for f in as_completed(futures):
            f.result()

    elapsed = time.perf_counter() - t_start
    return results, elapsed


def make_session():
    s = requests.Session()
    s.trust_env = False
    return s


def summarize(name, results, elapsed):
    ok = [r for r in results if r[1] == 200]
    err = [r for r in results if r[1] != 200]
    biz_200 = [r for r in ok if r[2] == 200]

    lat = sorted(r[0] for r in results)
    lat_ok = sorted(r[0] for r in ok)

    qps = len(results) / elapsed if elapsed else 0
    qps_ok = len(biz_200) / elapsed if elapsed else 0

    print(f"\n--- {name} ---")
    print(f"  总请求数     {len(results)}")
    print(f"  成功 (biz=200) {len(biz_200)}")
    print(f"  失败/异常     {len(err)}")
    if err:
        by = {}
        for r in err:
            key = f"HTTP {r[1]}" if r[1] != -1 else r[3] or "exc"
            by[key] = by.get(key, 0) + 1
        print(f"  错误分布     {by}")
    print(f"  总 QPS       {qps:.1f}")
    print(f"  成功 QPS     {qps_ok:.1f}")
    if lat:
        print(f"  延迟 P50     {percentile(lat, 50) * 1000:.1f} ms")
        print(f"  延迟 P90     {percentile(lat, 90) * 1000:.1f} ms")
        print(f"  延迟 P99     {percentile(lat, 99) * 1000:.1f} ms")
        print(f"  延迟 max     {lat[-1] * 1000:.1f} ms")
    return {
        "name": name,
        "requests": len(results),
        "success": len(biz_200),
        "errors": len(err),
        "qps": round(qps, 2),
        "qps_ok": round(qps_ok, 2),
        "p50_ms": round(percentile(lat, 50) * 1000, 1) if lat else 0,
        "p99_ms": round(percentile(lat, 99) * 1000, 1) if lat else 0,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--concurrency", default="10,50,100")
    ap.add_argument("--duration", type=float, default=15)
    ap.add_argument("--warmup", type=float, default=2)
    ap.add_argument("--message", default="灵山大佛有多高")
    ap.add_argument("--visitor-token", default="")
    ap.add_argument("--label", default="压测")
    args = ap.parse_args()

    headers = {}
    payload = {"message": args.message}
    if args.visitor_token:
        payload["visitorToken"] = args.visitor_token
        headers = {"Authorization": f"Bearer {args.visitor_token}"}

    concs = [int(x) for x in args.concurrency.split(",")]
    all_stats = []

    print(f"目标      {args.url}")
    print(f"并发档    {concs}")
    print(f"每档时长  {args.duration}s（预热 {args.warmup}s 不计）")
    print(f"问题      {args.message[:40]}")

    for c in concs:
        results, elapsed = run_once(
            args.url, payload, headers, c, args.duration, args.warmup
        )
        all_stats.append(summarize(f"{args.label} 并发={c}", results, elapsed))
        time.sleep(1)

    print("\n=== 汇总 ===")
    print(f"{'场景':<22} {'并发':>4} {'QPS':>8} {'P50':>8} {'P99':>8}")
    for s in all_stats:
        name = s["name"]
        conc = name.split("=")[-1]
        print(f"{name:<22} {conc:>4} {s['qps']:>8.1f} {s['p50_ms']:>7.1f}ms {s['p99_ms']:>7.1f}ms")

    with open("bench_result.json", "w", encoding="utf-8") as f:
        json.dump(all_stats, f, ensure_ascii=False, indent=2)
    print("\n结果已写入 bench_result.json")


if __name__ == "__main__":
    sys.exit(main())
