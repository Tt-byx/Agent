# 景区导览 AI 数字人系统

> 第十五届中国软件杯 A组赛题 | 出题方：锐捷网络

一个基于大模型的景区导览 AI 数字人系统，游客通过语音或文字与数字人对话，获取景区介绍、路线推荐等服务。

---

## 系统功能

**游客端**
- 数字人形象展示（2D半身像，口型同步+表情变化）
- 语音/文字双通道提问
- 基于景区知识库的精准问答（RAG，准确率 ≥ 90%）
- 根据兴趣偏好推荐游览路线

**管理后台**
- 知识库文档管理（PDF/Word/文本上传，自动向量化）
- 数字人形象与声音配置
- 游客对话记录与反馈分析
- 数据大屏（服务人次、热门问答、满意度趋势）

---

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Element Plus + ECharts |
| Java 后端 | Spring Boot + MyBatis-Plus + MySQL |
| Python 后端 | FastAPI |
| 向量数据库 | ChromaDB |
| 大模型 | 通义千问（Qwen）/ 智谱 GLM API |
| 语音识别 | FunASR（阿里开源） |
| 语音合成 | CosyVoice（阿里开源） |
| 数字人驱动 | SadTalker / MuseTalk |
| 部署 | Docker + Docker Compose |

---

## 系统架构

```
游客端(Vue3)  ←HTTP/WS→  Java后端(Spring Boot)  ←HTTP→  Python后端(FastAPI)
                                ↕                              ↕
                              MySQL                      ChromaDB / 大模型API
                                                    FunASR / CosyVoice / SadTalker
```

- **Java后端** 是面向前端的唯一入口，管理所有业务数据
- **Python后端** 只负责AI能力（RAG、ASR、TTS、数字人驱动）
- **前端** 通过 WebSocket 与 Java 后端保持实时对话通道

---

## 项目结构

```
Scenic_Area_Services/
├── frontend/          # Vue 3 前端项目（游客端 + 管理后台）
├── backend-java/      # Spring Boot 后端（业务网关）
├── backend-python/    # FastAPI 后端（AI 能力服务）
├── docker/            # Docker 配置文件
├── doc/               # 项目文档（问答记录、设计文档等）
├── git-init.ps1       # Git 仓库一键初始化脚本
├── .gitignore         # Git 忽略规则
├── README.md          # 项目说明（本文件）
└── CLAUDE.md          # AI 辅助开发总控文档
```

---

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/Tt-byx/Scenic_Area_Services.git
cd Scenic_Area_Services

# 启动各服务（详见各子目录 README）
# 前端
cd frontend && npm install && npm run dev

# Java 后端
cd backend-java && mvn spring-boot:run

# Python 后端
cd backend-python && pip install -r requirements.txt && uvicorn main:app --reload
```

---

## 开发路线图

- [x] Phase 0：项目骨架搭建与文字对话全链路跑通
- [ ] Phase 1：RAG 知识库构建（准确率 ≥ 90%）
- [ ] Phase 2：语音交互链路（延迟 < 5秒）
- [ ] Phase 3：数字人口型同步与表情变化
- [ ] Phase 4：个性化路线推荐
- [ ] Phase 5：管理后台完善与数据大屏
- [ ] Phase 6：联调打磨与交付

---

## 团队

- [Tt-byx](https://github.com/Tt-byx)

---

## License

本项目仅供比赛使用。
