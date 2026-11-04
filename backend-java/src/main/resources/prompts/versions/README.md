# Prompt 版本管理

System prompt 的版本化管理，配套评估机制。

## 目录结构

```
prompts/
├── scenic-agent-system.txt      ← 当前生效的版本（AgentLoop 加载）
├── versions/
│   ├── v1-baseline.txt          ← 2026-09-28 基线
│   ├── v2-*.txt                 ← 后续改进版
│   └── README.md                ← 本文件
└── ../tests/prompt-eval/        ← 评估对比脚本
```

## 版本命名规范

```
v{序号}-{主题}.txt

示例：
  v1-baseline.txt       基线（2026-09-28 上线）
  v2-concise.txt        精简回答（改「3-5 句」为「1-2 句」）
  v3-fewshot.txt        加 few-shot 示例
  v4-tool-routing.txt   改进工具使用规则
```

## 评估流程

**改 prompt 之前**：
1. 把当前版本备份到 `versions/v{N}-*.txt`
2. 记录改动点（改了什么、为什么改）
3. 跑基线评估（`compare.py --baseline v1`），记录指标

**改 prompt 之后**：
1. 写新版本到 `versions/v{N+1}-*.txt`
2. 跑对比评估（`compare.py --baseline v1 --candidate v2`）
3. 看指标：新版本是否更好？
4. 如果更好 → 覆盖 `scenic-agent-system.txt`，更新 CHANGELOG
5. 如果更差 → 回滚，分析原因

## 评估指标

| 指标 | 定义 | 期望 |
|---|---|---|
| `factHitRate` | 答案包含期望事实的比例 | 越高越好 |
| `avgLength` | 答案平均长度（字） | 越短越好（简洁） |
| `markdownRate` | 答案含 Markdown 标记的比例 | 应为 0（要求纯文本） |
| `toolCallRate` | 调用工具的比例 | 视问题类型，不强求 |

## 成本

评估会调 LLM，107 题全量约 0.5 元（flash）。
快速对比用 20 题小样本，约 0.1 元。
