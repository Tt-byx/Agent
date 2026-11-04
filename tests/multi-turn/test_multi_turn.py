"""
多轮对话测试：指代消解 + 上下文保留 + 记忆一致性

用 messages 数组模拟多轮对话（直接调 LLM，绕过应用），看模型能不能：
1. 指代消解：「它」「那个」指什么
2. 上下文保留：早期信息还在不在
3. 记忆一致：跨轮的偏好会不会记住

产出：多轮对话测试报告
"""
import json
import os
import time

import requests

API_URL = "https://api.deepseek.com/v1/chat/completions"
MODEL = "deepseek-flash"


def load_api_key():
    for path in [".env", "../../.env", "../.env"]:
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    if line.strip().startswith("SCENIC_LLM_API_KEY="):
                        return line.split("=", 1)[1].strip()
    raise RuntimeError("找不到 API key")


def load_prompt():
    p = "../../backend-java/src/main/resources/prompts/scenic-agent-system.txt"
    with open(p, encoding="utf-8") as f:
        prompt = f.read()
    return prompt.split("【工作方式")[0] + "\n\n请直接用你的知识回答，不要说要调用工具。回答简洁。"


def chat(api_key, system_prompt, history, user_msg):
    messages = [{"role": "system", "content": system_prompt}]
    messages += history
    messages.append({"role": "user", "content": user_msg})
    r = requests.post(API_URL, json={
        "model": MODEL,
        "messages": messages,
        "temperature": 0.3,
        "max_tokens": 250,
    }, headers={"Authorization": f"Bearer {api_key}"}, timeout=60)
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


# 测试场景：多轮对话
# (场景名, 对话轮次列表, 最后检查点, 期望包含)
SCENARIOS = [
    # 指代消解
    ("指代消解-大佛", [
        ("user", "灵山大佛有多高？"),
        ("assistant", None),  # 第一轮回答（我们会填）
        ("user", "它用了多少铜？"),  # 「它」= 大佛
    ], "725", "「它」应指灵山大佛，答案含 725 吨"),

    ("指代消解-梵宫", [
        ("user", "灵山梵宫的建筑面积是多少？"),
        ("assistant", None),
        ("user", "它的造价是多少？"),  # 「它」= 梵宫
    ], "18", "「它」应指梵宫，答案含 18 亿"),

    ("指代消解-演出", [
        ("user", "九龙灌浴几点开始表演？"),
        ("assistant", None),
        ("user", "那个演出有多长时间？"),  # 「那个」= 九龙灌浴
    ], "分钟", "「那个」应指九龙灌浴，答案含时长"),

    # 上下文保留（长对话）
    ("上下文保留-早期信息", [
        ("user", "灵山大佛通高多少米？"),
        ("assistant", None),
        ("user", "门票多少钱？"),
        ("assistant", None),
        ("user", "梵宫在哪？"),
        ("assistant", None),
        ("user", "我刚才问的大佛是多高？"),  # 3 轮后问早期信息
    ], "88", "3 轮后仍记得大佛高 88 米"),

    # 记忆一致性（偏好）
    ("记忆一致性-带小孩", [
        ("user", "我带了小孩一起来玩。"),
        ("assistant", None),
        ("user", "带小孩推荐玩什么？"),  # 应该记得「带小孩」
    ], "小孩", "应记得「带小孩」，推荐亲子内容"),

    # 混合：指代 + 记忆
    ("混合-指代+偏好", [
        ("user", "灵山大佛有多高？"),
        ("assistant", None),
        ("user", "我不吃辣，附近有吃饭的地方吗？"),
        ("assistant", None),
        ("user", "那它附近有什么吃的？"),  # 「它」= 大佛；应记得「不吃辣」
    ], "大佛", "「它」指大佛，且记得「不吃辣」"),
]


def main():
    api_key = load_api_key()
    system_prompt = load_prompt()
    print(f"用 {MODEL} 跑 {len(SCENARIOS)} 个多轮场景\n")

    results = []
    for name, turns, expect_key, note in SCENARIOS:
        history = []
        last_answer = ""
        for role, content in turns:
            if role == "user":
                answer = chat(api_key, system_prompt, history, content)
                history.append({"role": "user", "content": content})
                history.append({"role": "assistant", "content": answer})
                last_answer = answer
            # assistant 轮的 None 只是占位

        # 检查最后答案
        hit = expect_key in last_answer
        results.append({
            "scenario": name,
            "note": note,
            "expect": expect_key,
            "answer": last_answer[:150],
            "hit": hit,
        })
        status = "✓" if hit else "✗"
        print(f"[{status}] {name}")
        print(f"   期望含: {expect_key}")
        print(f"   答案: {last_answer[:80]}...\n")
        time.sleep(0.3)

    # 统计
    total = len(results)
    hits = sum(r["hit"] for r in results)
    print("=" * 60)
    print(f"总场景   {total}")
    print(f"通过     {hits}  ({hits/total:.0%})")

    with open("multi-turn-result.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n详细结果已写入 multi-turn-result.json")


if __name__ == "__main__":
    main()
