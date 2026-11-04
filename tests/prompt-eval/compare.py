"""
Prompt 版本对比评估

用法：
  python compare.py --baseline v1-baseline --candidate v2-concise --sample 20

原理：
  1. 从评测集取小样本（默认 20 题）
  2. 对每个 prompt 版本，用「system=prompt, user=问题」调 LLM
  3. 检查答案是否含期望事实（factHitRate）
  4. 统计答案长度、Markdown 使用率等
  5. 输出对比表

注意：
  - 这是「纯生成」评估（不给检索结果），测的是 prompt 对生成风格/事实命中率的影响
  - 如果要测 RAG 生成质量，需要把检索结果拼进 prompt（后续可扩展）
  - 会烧少量 token（20 题 × 2 版本 ≈ 0.1 元，flash 模型）
"""
import argparse
import json
import os
import re
import sys
import time

import requests

API_URL = "https://api.deepseek.com/v1/chat/completions"
MODEL = "deepseek-flash"
EVAL_SET = "../../backend-java/src/main/resources/rag/eval-dataset.tsv"
PROMPT_DIR = "../../backend-java/src/main/resources/prompts/versions"



# 中文数字 -> 阿拉伯数字（用于事实匹配）
# 「八十八米」->「88米」，让 expectedFact「88米」能匹配上
CN_NUMS = {
    '零': '0', '一': '1', '二': '2', '三': '3', '四': '4',
    '五': '5', '六': '6', '七': '7', '八': '8', '九': '9', '十': '10',
    '百': '100', '千': '1000', '万': '10000',
}
def normalize_numbers(text):
    """把中文数字转成阿拉伯数字，并去空格，便于事实匹配"""
    text = text.replace(' ', '').replace('\u3000', '')
    # 先处理「一千五百六十」这种复合数
    # 简单策略：「X百Y十Z」-> X*100+Y*10+Z
    text = re.sub(r'([一二三四五六七八九])千([一二三四五六七八九])百([一二三四五六七八九])十([一二三四五六七八九])',
                  lambda m: str(CN_NUMS[m.group(1)]*1000 + CN_NUMS[m.group(2)]*100 + CN_NUMS[m.group(3)]*10 + CN_NUMS[m.group(4)]), text)
    text = re.sub(r'([一二三四五六七八九])百([一二三四五六七八九])十([一二三四五六七八九])',
                  lambda m: str(CN_NUMS[m.group(1)]*100 + CN_NUMS[m.group(2)]*10 + CN_NUMS[m.group(3)]), text)
    text = re.sub(r'([一二三四五六七八九])百([一二三四五六七八九])',
                  lambda m: str(CN_NUMS[m.group(1)]*100 + CN_NUMS[m.group(2)]), text)
    text = re.sub(r'([一二三四五六七八九])千([一二三四五六七八九])百',
                  lambda m: str(CN_NUMS[m.group(1)]*1000 + CN_NUMS[m.group(2)]*100), text)
    text = re.sub(r'([一二三四五六七八九])千',
                  lambda m: str(CN_NUMS[m.group(1)]*1000), text)
    text = re.sub(r'([一二三四五六七八九])百',
                  lambda m: str(CN_NUMS[m.group(1)]*100), text)
    # 两位数「X十Y」
    text = re.sub(r'([一二三四五六七八九])十([一二三四五六七八九])',
                  lambda m: str(CN_NUMS[m.group(1)] * 10 + CN_NUMS[m.group(2)]), text)
    text = re.sub(r'十([一二三四五六七八九])', lambda m: '1' + CN_NUMS[m.group(1)], text)
    text = re.sub(r'([一二三四五六七八九])十', lambda m: str(CN_NUMS[m.group(1)] * 10) + '0', text)
    # 单字数字（排除已转换的）
    for k, v in CN_NUMS.items():
        if k in '百千万十':
            continue
        text = text.replace(k, v)
    return text
def normalize_numbers(text):
    """把中文数字转成阿拉伯数字，便于事实匹配"""
    # 先处理「八十八」这种两位数
    text = re.sub(r'([一二三四五六七八九])十([一二三四五六七八九])',
                  lambda m: str(CN_NUMS[m.group(1)] * 10 + CN_NUMS[m.group(2)]), text)
    # 再处理「十X」和「X十」
    text = re.sub(r'十([一二三四五六七八九])', lambda m: '1' + CN_NUMS[m.group(1)], text)
    text = re.sub(r'([一二三四五六七八九])十', lambda m: str(CN_NUMS[m.group(1)] * 10) + '0', text)
    # 单字数字
    for k, v in CN_NUMS.items():
        text = text.replace(k, v)
    return text

def load_api_key():
    for path in [".env", "../../.env", "../.env"]:
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("SCENIC_LLM_API_KEY="):
                        return line.split("=", 1)[1]
    raise RuntimeError("找不到 SCENIC_LLM_API_KEY，检查 .env")


def load_eval_set(sample=20):
    items = []
    with open(EVAL_SET, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("﻿")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                items.append({"q": parts[0], "fact": parts[1]})
    # 均匀采样（覆盖不同分类）
    step = max(1, len(items) // sample)
    return items[::step][:sample]


def call_llm(api_key, system_prompt, question):
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": question},
        ],
        "temperature": 0.3,
        "max_tokens": 300,
    }
    r = requests.post(API_URL, json=payload,
                      headers={"Authorization": f"Bearer {api_key}"},
                      timeout=60)
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def evaluate_version(api_key, prompt_path, items, label, no_tools=False):
    with open(prompt_path, encoding="utf-8") as f:
        system_prompt = f.read()

    # --no-tools：剥离工具调用规则，只要回答格式
    # 真实 prompt 里有「必须调用 search_knowledge_base」等工具规则，
    # 但评估脚本不提供工具，模型会吐出工具调用文本而不是答案。
    if no_tools:
        system_prompt = system_prompt.split("【工作方式")[0] + "\n\n请直接用你的知识回答问题，不要说要调用工具。回答要简洁，3-5 句话。"

    results = []
    for it in items:
        try:
            answer = call_llm(api_key, system_prompt, it["q"])
        except Exception as e:
            answer = f"[ERROR] {e}"
        # 指标
        # 数字归一化后匹配（「八十八米」能匹配「88米」）
        norm_answer = normalize_numbers(answer)
        norm_fact = normalize_numbers(it["fact"])
        # 再去空格（防御性）
        norm_answer = norm_answer.replace(" ", "")
        norm_fact = norm_fact.replace(" ", "")
        hit = norm_fact in norm_answer if it["fact"] else False
        md = bool(re.search(r"\*\*|##|```|^\d+\.|^- ", answer, re.M))
        results.append({
            "q": it["q"], "fact": it["fact"],
            "answer": answer, "hit": hit,
            "len": len(answer), "markdown": md,
        })
        time.sleep(0.2)  # 避免限流

    total = len(results)
    hits = sum(r["hit"] for r in results)
    mds = sum(r["markdown"] for r in results)
    avg_len = sum(r["len"] for r in results) / total if total else 0

    return {
        "label": label,
        "total": total,
        "factHitRate": hits / total if total else 0,
        "avgLength": round(avg_len, 1),
        "markdownRate": mds / total if total else 0,
        "details": results,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="基线 prompt 文件名（不含 .txt）")
    ap.add_argument("--candidate", help="候选 prompt 文件名（不含 .txt），可选")
    ap.add_argument("--sample", type=int, default=20)
    ap.add_argument("--out", default="prompt-eval-result.json")
    ap.add_argument("--no-tools", action="store_true",
                    help="用简化 prompt（直接回答），跳过工具调用，只测生成质量")
    args = ap.parse_args()

    api_key = load_api_key()
    items = load_eval_set(args.sample)
    no_tools = args.no_tools
    print(f"评测集：{len(items)} 题（小样本）\n")

    baseline_path = os.path.join(PROMPT_DIR, args.baseline + ".txt")
    print(f"评估基线：{args.baseline}")
    b = evaluate_version(api_key, baseline_path, items, args.baseline, no_tools)

    c = None
    if args.candidate:
        candidate_path = os.path.join(PROMPT_DIR, args.candidate + ".txt")
        print(f"评估候选：{args.candidate}")
        c = evaluate_version(api_key, candidate_path, items, args.candidate, no_tools)

    # 输出对比
    print("\n" + "=" * 60)
    print(f"{'指标':<16} {'基线 ' + args.baseline:<20}", end="")
    if c:
        print(f"{'候选 ' + args.candidate:<20} 变化")
    else:
        print()
    print("-" * 60)
    for k, name in [("factHitRate", "事实命中率"), ("avgLength", "平均长度"),
                    ("markdownRate", "Markdown率")]:
        bval = b[k]
        line = f"{name:<16} {bval:<20.3f}"
        if c:
            cval = c[k]
            delta = cval - bval
            arrow = "↑" if delta > 0 else ("↓" if delta < 0 else "=")
            line += f"{cval:<20.3f} {arrow} {delta:+.3f}"
        print(line)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"baseline": b, "candidate": c}, f, ensure_ascii=False, indent=2)
    print(f"\n详细结果已写入 {args.out}")

    # 输出几个样本答案
    print("\n=== 基线样本答案（前 2 题）===")
    for r in b["details"][:2]:
        print(f"  Q: {r['q']}")
        print(f"  A: {r['answer'][:80]}...")
        print(f"  命中事实：{'✓' if r['hit'] else '✗'}（期望 {r['fact']}）\n")

    if c:
        print("=== 候选样本答案（前 2 题）===")
        for r in c["details"][:2]:
            print(f"  Q: {r['q']}")
            print(f"  A: {r['answer'][:80]}...")
            print(f"  命中事实：{'✓' if r['hit'] else '✗'}（期望 {r['fact']}）\n")


if __name__ == "__main__":
    sys.exit(main())
