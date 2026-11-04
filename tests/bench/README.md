# 压测脚本

统计 QPS / P50 / P99 / 错误率。用于评估框架吞吐与真实链路延迟。

## 快速开始

```bash
# 1. 起探针（必须关限流，否则会打到阈值）
docker run -d --name bench-probe --network agent_default -p 10088:10088 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SCENIC_MYSQL_HOST=mysql -e SCENIC_PG_HOST=pgvector -e SPRING_DATA_REDIS_HOST=redis \
  -e SCENIC_LLM_API_KEY=<key> -e SCENIC_EMBEDDING_API_KEY=<key> \
  --entrypoint sh agent-app -c "java -jar app.jar --server.port=10088 --gateway.rate-limit.enabled=false"

# 2. 等启动
sleep 28 && curl http://localhost:10088/api/system/health

# 3. 基准压测（不调 LLM，测框架吞吐）
python bench.py --url http://localhost:10088/api/auth/guest \
    --concurrency 10,50,100 --duration 15 --label "基准"

# 4. 缓存命中压测（先预热一次）
TK=$(curl -s -X POST http://localhost:10088/api/auth/guest | jq -r .data.visitorToken)
curl -s -X POST http://localhost:10088/api/chat -H "Content-Type: application/json" \
  -d "{\"message\":\"你好\",\"visitorToken\":\"$TK\"}" > /dev/null
python bench.py --url http://localhost:10088/api/chat \
    --message "你好" --visitor-token "$TK" \
    --concurrency 10,50,100 --label "缓存命中"
```

## 测试用例的选择（重要）

| 想测什么 | 用什么问题 | 原因 |
|---|---|---|
| 框架吞吐 | `/api/auth/guest` | 不调 LLM，纯框架路径 |
| 缓存命中 | 「你好」类闲聊 | 不调工具，会写入语义缓存 |
| 真实链路 | 知识库问题 | 调 searchKnowledgeBase，**不写缓存** |

**坑**：知识库问题（如「灵山大佛有多高」）永远测不出缓存效果。
项目规则是「调用过工具的轮次不写缓存」（防止时效性数据被缓存）。

## 已知约束

- **Windows 下 `requests.Session` 不是线程安全的**，必须每个 worker 独占一个
  （脚本里已处理，改代码时注意别退回去）
- 压测会真实调 LLM，**烧 token**。缓存命中路径不烧
- 单机 Docker 部署，数据不代表生产环境

## 结果

`bench_result.json` 会写到脚本运行目录。
完整分析见 `docs/压测报告.md`。
