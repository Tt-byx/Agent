"""
LLM 驱动的情感分析服务
使用 MiMo API 分析文本情感，输出细粒度情感标签和对应的 Live2D 表情。
"""
import json
import logging
from functools import lru_cache

from openai import AsyncOpenAI
from app.core.config import settings

logger = logging.getLogger(__name__)

_client = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(
            api_key=settings.mimo_api_key or "not-set",
            base_url=settings.mimo_base_url,
        )
    return _client


SENTIMENT_PROMPT = """分析以下文本的情感倾向。严格按JSON格式返回，不要输出其他内容。

文本：{text}

返回格式：
{{"sentiment": "positive/neutral/negative", "emotion": "happy/surprised/grateful/confused/angry/worried/excited/sad", "confidence": 0.0到1.0}}

说明：
- sentiment: 整体情感极性
- emotion: 细粒度情感类别
- confidence: 置信度

只返回JSON，不要任何解释。"""


# emotion → Live2D 表情映射
EMOTION_EXPRESSION_MAP = {
    "happy": "Smile",
    "excited": "Smile",
    "grateful": "Smile",
    "surprised": "Star",
    "confused": "Circle",
    "angry": "Angry",
    "worried": "Cry",
    "sad": "Cry",
}

# 降级方案：关键词检测（扩展版，覆盖更多场景）
KEYWORD_FALLBACK = {
    "Cry": ["抱歉", "无法", "不知道", "没有找到", "暂时无法", "错误", "失败", "遗憾", "对不起",
            "可惜", "伤心", "难过", "唉", "糟糕", "不幸", "痛", "哭", "失望", "遗憾的是"],
    "Smile": ["恭喜", "太好了", "开心", "高兴", "快乐", "棒", "赞", "喜欢", "美丽", "精彩",
              "😊", "😄", "欢迎", "祝", "感谢", "谢谢", "太棒了", "非常好", "真不错",
              "哈哈", "嘿嘿", "幸福", "满意", "舒服", "享受", "美妙", "壮观"],
    "Angry": ["注意", "小心", "警告", "禁止", "危险", "请勿", "不要", "严禁",
              "当心", "务必", "切勿", "罚款", "违规", "处罚"],
    "Star": ["？", "吗", "呢", "什么", "怎么", "为什么", "请问",
             "哇", "天哪", "居然", "竟然", "没想到", "真的吗", "啊", "哎呀"],
}


def _keyword_fallback(text: str) -> dict:
    """关键词降级方案"""
    for expr, keywords in KEYWORD_FALLBACK.items():
        if any(w in text for w in keywords):
            if expr == "Smile":
                sentiment, emotion = "positive", "happy"
            elif expr == "Cry":
                sentiment, emotion = "negative", "sad"
            elif expr == "Angry":
                sentiment, emotion = "negative", "angry"
            elif expr == "Star":
                sentiment, emotion = "neutral", "surprised"
            else:
                sentiment, emotion = "neutral", "neutral"
            return {"sentiment": sentiment, "emotion": emotion, "expression": expr, "confidence": 0.6}
    return {"sentiment": "neutral", "emotion": "neutral", "expression": "Normal", "confidence": 0.3}


async def analyze_sentiment(text: str) -> dict:
    """
    分析文本情感（关键词方式，快速可靠，不依赖网络）
    返回 {sentiment, emotion, expression, confidence}
    """
    if not text or not text.strip():
        return {"sentiment": "neutral", "emotion": "neutral", "expression": "Normal", "confidence": 0.0}
    return _keyword_fallback(text)
