"""情感分析 API"""
import logging
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from app.services.sentiment_service import analyze_sentiment

logger = logging.getLogger(__name__)
router = APIRouter()


class SentimentRequest(BaseModel):
    text: str


class SentimentResponse(BaseModel):
    sentiment: str
    emotion: str
    expression: str
    confidence: float


@router.post("/sentiment", response_model=SentimentResponse)
async def sentiment_endpoint(request: SentimentRequest):
    """分析文本情感"""
    if not request.text or not request.text.strip():
        return SentimentResponse(sentiment="neutral", emotion="neutral", expression="Normal", confidence=0.0)
    try:
        result = await analyze_sentiment(request.text)
        return SentimentResponse(**result)
    except Exception as e:
        logger.error(f"情感分析 API 错误: {e}")
        return SentimentResponse(sentiment="neutral", emotion="neutral", expression="Normal", confidence=0.0)
