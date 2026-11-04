# 景区 AI 导览 Agent

> 基于 LangChain4j 的 ReAct Agent + 混合检索 RAG，为灵山胜境景区提供智能导览服务。

[![CI](https://github.com/Tt-byx/Agent/actions/workflows/ci.yml/badge.svg)](https://github.com/Tt-byx/Agent/actions/workflows/ci.yml)

## 核心能力

| 能力 | 说明 | 量化指标 |
|---|---|---|
| **ReAct Agent** | 多步推理、工具调用、自纠错 | 7 个工具（知识库检索/天气/票价/演出时间/路线/状态/记忆） |
| **混合检索 RAG** | 向量 + BM25 + 重排 | Hit@1 0.822（vs 纯向量 0.607，+35%） |
| **语义缓存** | 向量相似度命中，跳过整条链路 | 命中提速 23 倍（6224ms → 264ms，优化后 58ms） |
| **高可用** | 限流/幂等/熔断/降级/短路 | 8 项高可用测试全通过 |
| **可观测性** | Token/成本/延迟/Grafana | 成本可精确到元，缓存节省可量化 |
| **Prompt 工程** | 版本管理 + 自动评估 | v2 改进后事实命中率 +10% |
| **幻觉测试** | 陷阱问题量化编造率 | 明确幻觉率 9% |
| **多轮对话** | 指代消解/上下文保留/记忆一致 | 指代消解 3/3、记忆一致 2/2 |

## 技术栈

**后端**：Java 17 · Spring Boot 3.3 · LangChain4j · MySQL 8 · Redis 7 · pgvector
**前端**：Vue 3 · Element Plus · ECharts
**部署**：Docker Compose（4 容器：app / mysql / redis / pgvector）
**测试**：JUnit · 自研评测集（107 题）· 压测/幻觉/多轮测试脚本

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      浏览器（游客端/管理端）                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP / SSE
┌──────────────────────────▼──────────────────────────────────┐
│                    Spring Boot 应用                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ChatController (REST + SSE)                         │   │
│  │    ↓                                                  │   │
│  │  AgentLoop (ReAct: 思考 → 工具 → 观察 → 再思考)         │   │
│  │    ↓                    ↓                             │   │
│  │  RoutedChatModel    AgentToolbox                      │   │
│  │  (主/备模型路由)       (7 个工具 + 超时重试)              │   │
│  │    ↓                                                  │   │
│  │  SemanticCache ←→ RagRetriever (向量 + BM25 + 重排)    │   │
│  │    ↓                                                  │   │
│  │  ShortTermMemory (滑窗+摘要)  LongTermMemory (偏好)     │   │
│  └─────────────────────────────────────────────────────┘   │
└──────────┬─────────────────┬──────────────────┬────────────┘
           ↓                 ↓                  ↓
        MySQL              Redis             pgvector
       (业务数据)        (缓存/限流/幂等)     (向量检索)
```

## 快速开始

```bash
# 1. 克隆
git clone https://github.com/Tt-byx/Agent.git
cd Agent

# 2. 配置密钥（参考 backend-java/src/main/resources/application.yml）
cp .env.example .env
vim .env  # 填入 SCENIC_LLM_API_KEY、SCENIC_EMBEDDING_API_KEY

# 3. 启动
docker compose up -d

# 4. 访问
open http://localhost:5173          # 游客端
open http://localhost:5173/admin    # 管理端
```

## 测试与评测

### RAG 检索评测（107 题）

```bash
curl -X POST http://localhost:10086/api/rag/eval \
  -H "Authorization: Bearer <admin-token>"
```

结果（Hit@1）：纯向量 0.607 → 混合 0.720 → **混合+重排 0.822**

### 压测

```bash
cd tests/bench
python bench.py --url http://localhost:10088/api/auth/guest \
    --concurrency 10,50,100 --duration 15
```

结果：框架吞吐 1866 QPS（50 并发拐点），瓶颈 89% 在 LLM 调用

### 幻觉测试

```bash
cd tests/hallucination
python test_hallucination.py
```

结果：11 个陷阱问题，明确幻觉率 9%

### 多轮对话测试

```bash
cd tests/multi-turn
python test_multi_turn.py
```

结果：指代消解 3/3、记忆一致 2/2

## 项目结构

```
Agent/
├── backend-java/              # Spring Boot 后端
│   ├── src/main/java/com/scenic/ai/
│   │   ├── agent/            # AgentLoop、工具
│   │   ├── gateway/          # 缓存、限流、幂等、路由
│   │   ├── memory/           # 短期/长期记忆
│   │   ├── rag/              # 检索、重排、向量库
│   │   └── controller/       # REST API
│   └── src/main/resources/
│       ├── prompts/          # System prompt（版本管理）
│       └── rag/eval-dataset.tsv  # 评测集（107 题）
├── frontend/                 # Vue 3 前端
├── tests/                    # 测试脚本
│   ├── bench/                # 压测
│   ├── hallucination/        # 幻觉测试
│   ├── multi-turn/           # 多轮对话测试
│   └── prompt-eval/          # Prompt 版本对比
├── docs/                     # 文档（测试报告、缺陷总结）
└── docker-compose.yml
```

## 核心设计

### 1. 语义缓存（不是普通缓存）

```
query → 向量化 → 在 pgvector 里找相似问题 → 相似度 ≥ 0.92 就直接返回答案
```

- **不是** key-value 缓存（那只能匹配完全相同的问题）
- 相似问题（「多高」vs「高度是多少」）也能命中
- 阈值 0.92 是调优过的：太低会误命中（「大佛多高」命中「梵宫多高」）

### 2. 失败短路（Fast Failure）

依赖挂掉时，快速失败而不是等超时：

```
第一次调用失败 → 开启 10s 短路窗口 → 窗口内直接抛异常
```

实测：Redis 挂掉时，从「每次等 53 秒」压到「第一次 4.5s，之后 0.02s」

### 3. 按工具类型的缓存规则

```java
// 只调了知识库类工具 → 可缓存（答案稳定）
// 调了实时类工具 → 不缓存（天气/演出时间会变）
if (onlyCacheableTools(trace)) {
    semanticCache.store(question, answer);
}
```

否则知识库问答（最需要缓存的场景）反而缓存不了。

## 文档

- [高可用测试报告](docs/高可用测试报告.md) — 8 项高可用验证
- [压测报告](docs/压测报告.md) — QPS / P99 / 瓶颈分析
- [RAG 评测报告](docs/RAG评测报告.md) — 107 题三策略对比
- [幻觉测试报告](docs/幻觉测试报告.md) — 陷阱问题量化编造率
- [多轮对话测试报告](docs/多轮对话测试报告.md) — 指代消解/记忆一致性
- [缺陷总结](docs/缺陷总结.md) — 33 项缺陷修复记录

## License

MIT
