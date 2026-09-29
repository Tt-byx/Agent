# 景区智能导览 Agent 系统

> 第十六届中国软件杯 A 组赛题（景区导览服务 AI 数字人）的 **Agent 化改造**：AI 主链路从 Python 迁移至 JVM，基于 LangChain4j 实现可自纠、可观测、有成本意识的 ReAct Agent。

**一句话定位**：不是"会动的数字人 + 问答机器人"，而是一个带三层记忆、故障能自纠、行为可评测、成本可度量的景区导览 Agent 内核。

---

## 核心结果

| 维度 | 结果 |
|---|---|
| 检索质量（自建 33 条评测集） | Hit@1 **54.5% → 90.9%**，MRR **0.674 → 0.934**（向量 + BM25 混合检索 + 重排） |
| Agent 可靠性 | 自研 ReAct 循环，5 项验收全过；注入必失败工具后**自行换参数重试**再优雅降级，全程无 500 |
| 性能 | 语义缓存命中后 **P50 3274ms → 201ms**，**QPS 2.5 → 23.1（约 9 倍）**，命中路径不调用模型（边际成本 0） |
| 记忆 | 三层记忆（Redis 滑窗 / 会话摘要 / pgvector 长期偏好），实测**跨会话召回用户偏好** |
| 高可用 | 同厂商双档主备模型路由（flash 主 / pro 备，主模型故障自动切换、用户零感知）+ Redis 限流 |
| 可观测 | Micrometer / Prometheus 指标（路由健康 / 缓存命中 / 工具失败 / token 成本）+ Grafana 面板 |

## 与常见方案的差异

- **同一份工具逻辑，两条通道复用**：`@Tool` 注解的业务方法既注册给模型（Function Calling），又通过 **MCP Server**（Streamable HTTP）暴露给外部客户端（Cherry Studio / Claude Desktop），解决工具接入的 M × N 重复对接。
- **自研循环而非框架黑盒**：AiServices 的内嵌工具循环不暴露最大步数、整体超时、逐步 trace 三个旋钮，而这正是可靠性需求所在。自研用 `ChatModel + ToolSpecifications + DefaultToolExecutor` 组装，工具声明仍复用 `@Tool`，无重复定义。
- **评测驱动**：每阶段以量化指标验收并跑回归；发现原定指标（Recall@5 +10pp）在 90.9% 基线上数学上不可达后，改用 Hit@1 / MRR——指标选型本身经过论证。
- **分工是物理的**：多 Agent 编排下，每个专家只持有白名单内的工具（子工具箱裁剪），而非提示词约定；专家工具箱不含委派工具，从结构上杜绝无限递归。

## 系统架构

```
            ┌─────────────────────────── JVM（Spring Boot 3）───────────────────────────┐
  Vue3 前端 │  Controller/WS → AgentLoop（ReAct 循环）                                   │
  ────────► │                     │                                                     │
  MCP 客户端│  MCP Server ────────┤  AgentToolbox（@Tool 反射生成 schema / 异常回灌 / 子工具箱） │
  ────────► │  (Streamable HTTP)  │        │                                            │
            │  Supervisor 编排 ───┘        ▼                                            │
            │                     混合检索（pgvector + BM25）→ Reranker                   │
            │  RoutedChatModel（flash 主 / pro 备降级）  SemanticCache（pgvector）      │
            │        │                                  │                               │
            └────────┼──────────────────────────────────┼───────────────────────────────┘
                     ▼                                  ▼
        DeepSeek（flash 主 / pro 备）   PostgreSQL + pgvector   Redis   MySQL
        硅基流动（embedding + rerank）
```

- 一次对话的完整路径：语义缓存查 → 记忆召回（长期偏好 + 会话上下文）→ ReAct 循环（按需检索 / 调工具）→ 强制收口 → 回写记忆与缓存，全程 Micrometer 打点。

## 技术栈

| 层 | 技术 |
|---|---|
| Agent 内核 | LangChain4j 1.20（自研 ReAct 循环 + Supervisor 编排） |
| 后端 | Spring Boot 3.3、MyBatis-Plus、WebSocket |
| 工具协议 | MCP Java SDK 2.0（`mcp-core` + `mcp-json-jackson2`，Streamable HTTP） |
| 检索 | PostgreSQL **pgvector**（向量）+ 自研 BM25（JVM 内）+ bge-reranker-v2-m3 重排 |
| 记忆/缓存 | Redis（滑窗 + 摘要）、pgvector（长期偏好 + 语义缓存） |
| 模型 | DeepSeek flash（主）/ deepseek-v4-pro（备）；硅基流动仅做 embedding + rerank |
| 观测 | Micrometer + Prometheus + Grafana（面板：`backend-java/docs/`） |

## 快速开始

依赖：JDK 17、Maven 3.9、MySQL 8、Redis 7、PostgreSQL 16+（含 pgvector 扩展）。

```bash
# 1. 配置密钥（不要提交到仓库）
#    方式一：环境变量 SCENIC_LLM_API_KEY / SCENIC_EMBEDDING_API_KEY
#    方式二：新建 backend-java/src/main/resources/application-local.yml（已被 .gitignore 忽略）
#            llm:
#              api-key: sk-xxx
#            embedding:
#              api-key: sk-xxx

# 2. 启动后端（默认端口 10086）
cd backend-java
mvn spring-boot:run

# 3. 前端
cd frontend && npm install && npm run dev
```

可选开关（命令行参数）：

| 参数 | 说明 |
|---|---|
| `--agent.multi.enabled=true` | 启用 Supervisor 多 Agent 编排（默认单 Agent，延迟更低） |
| `--agent.debug-tools.enabled=true` | 注册故障注入工具，演示工具失败后的自纠 |
| `--gateway.rate-limit.per-minute=20` | 每游客限流阈值 |
| `--llm.api-key=invalid` | 故意破坏主模型，验证主备降级（flash → pro） |

**MCP 接入**：在 Cherry Studio / Claude Desktop 中添加 Streamable HTTP 服务器，URL 填 `http://localhost:10086/mcp`，可发现并调用 3 个景区工具。

## 目录结构

```
├── backend-java/        # Spring Boot 后端：Agent 内核 + RAG + MCP Server + 网关（核心）
│   └── docs/            # Grafana 面板等工程交付物
├── langgraph-demo/      # Python LangGraph 对照 demo（复刻同一 ReAct 循环，不接主链路）
├── backend-python/      # 旧 Python 后端（已不被主链路依赖，仅存档）
├── frontend/            # Vue 3 前端（游客端 + 管理后台）
└── docs/                # 本地资料（改造方案、评测记录、答辩材料；不入库）
```

## 评测与压测

**检索质量**（33 条评测集，三种检索模式对比）：

| 指标 | 纯向量 | 混合 | 混合+重排 |
|---|---|---|---|
| Recall@5 | 0.909 | 0.909 | **0.970** |
| Hit@1 | 0.545 | 0.636 | **0.909** |
| MRR | 0.674 | 0.736 | **0.934** |

**压测**（自研负载脚本，指标口径与 JMeter 一致）：

| 场景 | 并发 | 成功率 | QPS | P50 | P95 |
|---|---|---|---|---|---|
| 缓存命中路径 | 8 | 85% | **23.1** | **201 ms** | 664 ms |
| 完整链路（检索+工具+模型） | 5 | 100% | 2.5 | 3274 ms | 3994 ms |

完整链路成本 ≈ 2400 输入 + 134 输出 token/次；缓存命中路径为 0。

## 已知局限

- 评测集 33 条为小样本，结论是方向性的；计划扩到 100+ 并加入对抗样本（相似问题不同答案）。
- 语义缓存对超短查询存在误命中风险（相似度 > 0.92），计划对短查询提高阈值。
- 缓存命中路径仍依赖一次 embedding 调用，压测下瓶颈在外部 embedding 接口并发；计划加查询向量本地缓存。
- 生成答案暂无引用溯源（不标注出自哪个切片），是可解释性的下一步。

## 开发路线

- [x] 架构收敛：删除数字人/语音链路，AI 主链路迁至 JVM（P1–P2）
- [x] RAG 迁移与重建：pgvector + JVM 内文档处理（P3）
- [x] 检索评测体系与优化：Hit@1 54.5%→90.9%（P4）
- [x] 工具体系：7 个工具 + 并行调用（P5）
- [x] MCP Server：Streamable HTTP + 协议验证（P6）
- [x] 自研 ReAct 循环与可靠性机制（P7）
- [x] 三层记忆 / 语义缓存 / 多模型路由 / 限流 / 压测 / 监控（P8）
- [x] Supervisor 多 Agent + LangGraph 对照（P9）
- [ ] 答案引用溯源；评测集扩充；查询向量本地缓存

## 团队

- [Tt-byx](https://github.com/Tt-byx)

---

本项目基于中国软件杯赛题开发，仅供学习与比赛使用。
