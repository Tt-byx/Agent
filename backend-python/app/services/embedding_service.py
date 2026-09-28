import asyncio
import hashlib
import logging
from openai import AsyncOpenAI
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)

_client = None

def _get_client():
    global _client
    if _client is None:
        _client = AsyncOpenAI(http_client=httpx.AsyncClient(trust_env=False,
            timeout=httpx.Timeout(timeout=30.0, connect=5.0, read=15.0, write=5.0)),
            api_key=settings.embedding_api_key or "not-set",
            base_url=settings.embedding_base_url,
        )
    return _client

BATCH_SIZE = 32

# ── Embedding 缓存（相同输入永远产生相同输出，完全安全） ──
_embed_cache: dict[str, list[float]] = {}
_CACHE_MAX = 512


async def embed_texts(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []

    client = _get_client()
    all_embeddings = []
    for i in range(0, len(texts), BATCH_SIZE):
        batch = texts[i : i + BATCH_SIZE]
        # 带重试的 embedding 调用（应对限流和网络错误）
        for attempt in range(3):
            try:
                response = await client.embeddings.create(
                    model=settings.embedding_model,
                    input=batch,
                )
                batch_embeddings = [item.embedding for item in response.data]
                all_embeddings.extend(batch_embeddings)
                break
            except Exception as e:
                if attempt < 2 and ("429" in str(e) or "rate" in str(e).lower()
                                     or "50" in str(e) or "timeout" in str(e).lower()
                                     or "connect" in str(e).lower()):
                    wait = 2 ** attempt  # 1s, 2s（缩短等待）
                    logger.warning(f"Embedding 调用失败，等待 {wait}s 后重试 ({attempt+1}/3): {e}")
                    await asyncio.sleep(wait)
                else:
                    raise

    return all_embeddings


async def embed_query(query: str) -> list[float]:
    """单条 query embedding，带 LRU 缓存"""
    cache_key = hashlib.md5(query.encode()).hexdigest()
    if cache_key in _embed_cache:
        return _embed_cache[cache_key]

    result = await embed_texts([query])
    embedding = result[0]

    # 简易 LRU：满了就清空一半
    if len(_embed_cache) >= _CACHE_MAX:
        keys = list(_embed_cache.keys())
        for k in keys[:_CACHE_MAX // 2]:
            del _embed_cache[k]

    _embed_cache[cache_key] = embedding
    return embedding
