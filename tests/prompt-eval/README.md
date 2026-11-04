# Prompt 版本对比评估

对比不同 system prompt 版本在评测集上的表现。

## 快速开始

```bash
cd tests/prompt-eval

# 单版本评估（基线）
python compare.py --baseline v1-baseline --sample 20 --no-tools

# 两版本对比
python compare.py --baseline v1-baseline --candidate v2-concise --sample 20 --no-tools
```

## 参数

- `--sample N`：取评测集的前 N 题（默认 20，小样本快速对比）
- `--no-tools`：**强烈推荐**。剥离 prompt 里的工具调用规则，用「直接回答」模式
  （否则模型会吐工具调用格式而不是答案）
- `--out`：输出 JSON 文件名

## 评估指标

| 指标 | 定义 | 期望 |
|---|---|---|
| `factHitRate` | 答案包含期望事实的比例 | 越高越好 |
| `avgLength` | 答案平均长度（字） | 越短越好（简洁） |
| `markdownRate` | 答案含 Markdown 标记的比例 | 应为 0（要求纯文本） |

## 匹配逻辑

- 中文数字归一化：「八十八米」→「88米」，「一千五百六十」→「1560」
- 去空格：「88 米」→「88米」
- 包含匹配（`fact in answer`）

**局限**：关键词匹配可能漏（同义词、改写），可能误判（数字出现在无关上下文）。
正式评估建议加 LLM 评分。

## 已知局限

1. **不测工具调用**：`--no-tools` 模式下模型不调工具，只测生成质量
2. **小样本**：20 题的结论有随机性，建议多次取平均
3. **不测 RAG 生成**：真实场景是「检索 + 生成」，这里只测生成

## 成本

flash 模型，20 题 × 2 版本 ≈ 0.1 元。
