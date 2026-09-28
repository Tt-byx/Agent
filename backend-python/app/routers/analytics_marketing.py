"""营销决策分析 API"""
import logging
from fastapi import APIRouter
from pydantic import BaseModel
from openai import AsyncOpenAI
from app.core.config import settings

logger = logging.getLogger(__name__)
router = APIRouter()

_client = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(
            api_key=settings.mimo_api_key or "not-set",
            base_url=settings.mimo_base_url,
        )
    return _client


class MarketingRequest(BaseModel):
    stats_summary: str  # 统计数据的文字描述


class MarketingResponse(BaseModel):
    suggestions: str


MARKETING_PROMPT = """你是景区运营数据分析专家。根据以下数据，给出3-5条具体的营销决策建议。

数据概览：
{stats}

要求：
1. 禁止使用 Markdown 符号
2. 用口语化中文，像运营顾问在跟管理层汇报
3. 每条建议要具体可执行，包含原因和预期效果
4. 总字数控制在200字以内
5. 只返回建议内容，不要其他说明"""


@router.post("/analytics/marketing", response_model=MarketingResponse)
async def marketing_analysis(request: MarketingRequest):
    """基于统计数据生成营销建议"""
    try:
        prompt = MARKETING_PROMPT.format(stats=request.stats_summary)
        response = await _get_client().chat.completions.create(
            model=settings.mimo_model,
            messages=[
                {"role": "system", "content": "你是景区运营数据分析专家，只返回分析建议。"},
                {"role": "user", "content": prompt},
            ],
            temperature=0.7,
            max_tokens=500,
        )
        content = response.choices[0].message.content or "暂无建议"
        return MarketingResponse(suggestions=content)
    except Exception as e:
        logger.error(f"营销分析失败: {e}")
        return MarketingResponse(suggestions="暂时无法生成营销建议，请稍后重试。")
