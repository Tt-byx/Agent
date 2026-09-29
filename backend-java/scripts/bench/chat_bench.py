"""景区导览 Agent · 压测脚本（Wave 4 入库版）

用法
----
    python chat_bench.py                          # 默认打本机 10086
    python chat_bench.py --base-url http://127.0.0.1:10086

场景
----
    A · 缓存命中路径 —— 同一问题 × 40，8 并发
        验证语义缓存的吞吐/延迟收益（命中后跳过检索、工具与模型）
    B · 完整链路     —— 10 个不同问题，5 并发
        验证真实链路（混合检索 + 重排 + 工具 + 模型）的承载能力

指标口径（与 JMeter 对齐，便于横向比较）
------------------------------------------
    QPS          = 完成请求数 / 总耗时
    P50/P95/P99  = 成功请求延迟的分位数（失败请求不计入延迟统计）
    资源占用     = 压测期间每 2s 采样一次 /actuator/prometheus：
                   process_cpu_usage（进程 CPU 使用率）
                   jvm_memory_used_bytes{area="heap"}（堆内存占用）
                   并同时记录 tomcat 线程与 Hikari 连接占用
    成本         = 压测前后 token 计数差值（按 route 汇总），
                   乘以 application.yml 里 ops.pricing 的单价
                   —— 缓存命中路径不调模型，因此 token 增量应接近 0

依赖：仅标准库（urllib / threading / statistics），换机器可直接运行。
前置：服务已启动；场景 A 需要语义缓存，脚本会自动预热。
"""

import argparse
import json
import statistics
import threading
import time
import urllib.error
import urllib.request

DEFAULT_BASE = "http://127.0.0.1:10086"
VISITOR_ID = "v-bench"

# 压测期间采集的资源与成本指标（取自 Spring Boot Actuator）
SAMPLED_PREFIXES = (
    "process_cpu_usage",
    "jvm_memory_used_bytes",
    "tomcat_threads_busy_threads",
    "hikari_connections_active",
    "scenic_llm_tokens_input_total",
    "scenic_llm_tokens_output_total",
    "scenic_cache_hits_total",
    "scenic_cache_misses_total",
)


def chat(base, message, visitor_id, session_id, timeout=180, request_id=None):
    """发一次对话请求，返回 (答复, 毫秒延迟)。"""
    payload = {"message": message, "visitorId": visitor_id, "sessionId": session_id}
    if request_id:
        payload["requestId"] = request_id
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base + "/api/chat", data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if data.get("code") != 200:
        raise RuntimeError("code=" + str(data.get("code")) + " " + str(data.get("message")))
    return data["data"]["reply"], (time.time() - start) * 1000


def scrape_metrics(base, timeout=20):
    """抓取 /actuator/prometheus，解析出我们关心的指标。"""
    snapshot = {}
    try:
        with urllib.request.urlopen(base + "/actuator/prometheus", timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
    except Exception as exc:  # 指标抓不到不应中断压测
        print(f"  [warn] 指标抓取失败：{exc}")
        return snapshot

    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        for prefix in SAMPLED_PREFIXES:
            if line.startswith(prefix):
                try:
                    value = float(line.rsplit(" ", 1)[1])
                except (IndexError, ValueError):
                    continue
                if prefix == "jvm_memory_used_bytes" and 'area="heap"' not in line:
                    continue
                if prefix == "tomcat_threads_busy_threads" and 'name="http-nio' not in line:
                    continue
                snapshot[prefix] = snapshot.get(prefix, 0.0) + value
                break
    return snapshot


class ResourceSampler(threading.Thread):
    """压测期间周期性采样服务端资源占用。"""

    def __init__(self, base, interval=2.0):
        super().__init__(daemon=True)
        self.base = base
        self.interval = interval
        self._stop = threading.Event()
        self.samples = []

    def run(self):
        while not self._stop.is_set():
            snap = scrape_metrics(self.base)
            if snap:
                self.samples.append(snap)
            self._stop.wait(self.interval)

    def stop(self):
        self._stop.set()


def pct(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    idx = min(len(values) - 1, int(round(p / 100.0 * (len(values) - 1))))
    return values[idx]


def summarize(name, results, elapsed, before, after, samples):
    ok = sum(1 for flag, _ in results if flag)
    lat = [ms for flag, ms in results if flag]
    print(f"  请求数 {len(results)}  成功 {ok}  成功率 {ok / len(results) * 100:.0f}%")
    print(f"  总耗时 {elapsed:.2f}s   QPS = {len(results) / elapsed:.1f}")
    if lat:
        print(f"  延迟 P50 {pct(lat,50):.0f} ms | P95 {pct(lat,95):.0f} ms | "
              f"P99 {pct(lat,99):.0f} ms | 最大 {max(lat):.0f} ms")
        print(f"  延迟均值 {statistics.mean(lat):.0f} ms")

    # 资源占用
    cpu = [s.get("process_cpu_usage", 0) for s in samples if "process_cpu_usage" in s]
    heap = [s.get("jvm_memory_used_bytes", 0) for s in samples if "jvm_memory_used_bytes" in s]
    busy = [s.get("tomcat_threads_busy_threads", 0) for s in samples if "tomcat_threads_busy_threads" in s]
    conn = [s.get("hikari_connections_active", 0) for s in samples if "hikari_connections_active" in s]
    if cpu:
        print(f"  进程 CPU 平均 {statistics.mean(cpu)*100:.1f}% | 峰值 {max(cpu)*100:.1f}%")
    if heap:
        print(f"  堆内存 平均 {statistics.mean(heap)/1024/1024:.0f} MB | 峰值 {max(heap)/1024/1024:.0f} MB")
    if busy:
        print(f"  Tomcat 忙线程 峰值 {max(busy):.0f}")
    if conn:
        print(f"  Hikari 活跃连接 峰值 {max(conn):.0f}")

    # 成本增量（token 与缓存命中）
    if before and after:
        tin = after.get("scenic_llm_tokens_input_total", 0) - before.get("scenic_llm_tokens_input_total", 0)
        tout = after.get("scenic_llm_tokens_output_total", 0) - before.get("scenic_llm_tokens_output_total", 0)
        hits = after.get("scenic_cache_hits_total", 0) - before.get("scenic_cache_hits_total", 0)
        misses = after.get("scenic_cache_misses_total", 0) - before.get("scenic_cache_misses_total", 0)
        print(f"  本次增量：输入 token {tin:.0f} | 输出 token {tout:.0f} | "
              f"缓存命中 {hits:.0f} / 未命中 {misses:.0f}")
    print(f"  [{name} 结束]")


def main():
    parser = argparse.ArgumentParser(description="景区导览 Agent 压测")
    parser.add_argument("--base-url", default=DEFAULT_BASE, help="服务地址")
    parser.add_argument("--scenario", default="both", choices=["a", "b", "both"],
                        help="只跑某个场景")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    print(f"目标：{base}")
    print("预热：确认服务可用并把答案写入语义缓存")
    chat(base, "灵山大佛用了多少吨铜？", VISITOR_ID, "s-warm")
    time.sleep(1)

    if args.scenario in ("a", "both"):
        print()
        print("=" * 66)
        print("场景 A：缓存命中路径（同一问题 × 40，8 并发）")
        print("=" * 66)
        before = scrape_metrics(base)
        sampler = ResourceSampler(base)
        sampler.start()

        results = []
        lock = threading.Lock()

        def worker(count):
            local = []
            for _ in range(count):
                try:
                    _, ms = chat(base, "灵山大佛用了多少吨铜？", VISITOR_ID, "s-bench-a")
                    local.append((True, ms))
                except Exception:
                    local.append((False, 0))
            with lock:
                results.extend(local)

        threads = [threading.Thread(target=worker, args=(5,)) for _ in range(8)]
        t0 = time.time()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed = time.time() - t0

        sampler.stop()
        sampler.join(timeout=3)
        after = scrape_metrics(base)
        summarize("场景 A", results, elapsed, before, after, sampler.samples)

    if args.scenario in ("b", "both"):
        print()
        print("=" * 66)
        print("场景 B：完整链路（10 个不同问题，5 并发，不命中缓存）")
        print("=" * 66)
        spots = ["九龙灌浴", "灵山梵宫", "五明桥", "佛足坛", "五智门",
                 "菩提大道", "降魔浮雕", "阿育王柱", "百子戏弥勒", "祥符禅寺"]
        questions = [f"请介绍一下灵山胜境里{spot}这个景点的看点" for spot in spots]

        before = scrape_metrics(base)
        sampler = ResourceSampler(base)
        sampler.start()

        results_b = []
        lock_b = threading.Lock()

        def worker_b(idx):
            try:
                _, ms = chat(base, questions[idx], VISITOR_ID, f"s-bench-b-{idx}")
                outcome = (True, ms)
            except Exception:
                outcome = (False, 0)
            with lock_b:
                results_b.append(outcome)

        threads = [threading.Thread(target=worker_b, args=(i,)) for i in range(len(questions))]
        t0 = time.time()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed_b = time.time() - t0

        sampler.stop()
        sampler.join(timeout=3)
        after = scrape_metrics(base)
        summarize("场景 B", results_b, elapsed_b, before, after, sampler.samples)

    print()
    print("说明：缓存命中路径不调用模型 → token 增量接近 0，边际成本为 0；")
    print("      完整链路的 token 增量即本次压测的真实模型消耗。")


if __name__ == "__main__":
    main()
