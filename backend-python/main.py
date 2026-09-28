import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.routers import chat, knowledge, sentiment, analytics_marketing, admin_ai
from app.services.vector_store import get_client

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(levelname)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时验证 ChromaDB 连接
    try:
        client = get_client()
        client.heartbeat()
        logger.info("ChromaDB 初始化成功")
    except Exception as e:
        logger.warning(f"ChromaDB 初始化失败: {e}")

    # 后台预加载 ASR 模型（不阻塞启动）
    # MiMo ASR 使用 API，无需预加载

    logger.info("后端就绪")
    yield


app = FastAPI(title="景区AI数字人 - Python后端", version="0.2.0", lifespan=lifespan)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:10086", "http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(chat.router, prefix="/api", tags=["chat"])
app.include_router(knowledge.router, prefix="/api", tags=["knowledge"])
app.include_router(sentiment.router, prefix="/api", tags=["sentiment"])
app.include_router(analytics_marketing.router, prefix="/api", tags=["marketing"])
app.include_router(admin_ai.router, prefix="/api", tags=["admin-ai"])


@app.get("/health")
async def health():
    return {"status": "ok", "service": "python-backend"}
