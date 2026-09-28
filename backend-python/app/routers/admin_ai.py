"""管理后台 AI 智能问答 — 面向运营人员的行为数据问答 + 画像营销推荐"""
import logging
from fastapi import APIRouter
from pydantic import BaseModel
from openai import AsyncOpenAI
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)
router = APIRouter()

_client = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(
            http_client=httpx.AsyncClient(trust_env=False),
            api_key=settings.mimo_api_key or "not-set",
            base_url=settings.mimo_base_url,
        )
    return _client


class AdminChatRequest(BaseModel):
    message: str
    behavior_context: str = ""  # Java 端传入的行为数据摘要


class AdminChatResponse(BaseModel):
    reply: str


class RecommendRequest(BaseModel):
    nickname: str = ""
    profile_tags: list[str] = []
    sentiment_summary: str = ""
    consumption_summary: str = ""
    conversation_snippet: str = ""


class RecommendResponse(BaseModel):
    suggestions: str


ADMIN_QA_PROMPT = """你是一位资深景区运营分析师，专门帮助运营人员分析游客行为数据并提供决策建议。

【数据上下文】
{behavior_context}

【回答要求】
1. 禁止使用 Markdown 符号，用纯文本回答
2. 用口语化中文，像运营顾问在跟管理层汇报
3. 如果数据中有具体数字，要引用这些数字来支持分析
4. 回答要简洁实用，控制在150字以内
5. 如果数据不足以回答问题，诚实说明"""


RECOMMEND_PROMPT = """你是一位景区营销专家，根据以下游客画像信息，生成个性化的营销话术和产品推荐。

【游客画像】
昵称：{nickname}
兴趣标签：{tags}
情感倾向：{sentiment}
消费记录：{consumption}
近期对话摘要：{snippet}

【输出要求】
1. 禁止使用 Markdown 符号
2. 生成3条具体的营销推荐，每条包含：推荐产品/活动 + 推荐话术 + 预期效果
3. 话术要自然亲切，像真人导游在推荐
4. 总字数控制在200字以内"""


@router.post("/admin-ai/chat", response_model=AdminChatResponse)
async def admin_chat(request: AdminChatRequest):
    """运营人员 AI 问答：结合行为数据上下文回答运营问题"""
    try:
        system_prompt = ADMIN_QA_PROMPT.format(
            behavior_context=request.behavior_context or "暂无行为数据"
        )
        response = await _get_client().chat.completions.create(
            model=settings.mimo_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": request.message},
            ],
            temperature=0.7,
            max_tokens=600,
        )
        reply = response.choices[0].message.content or "暂无回答"
        return AdminChatResponse(reply=reply)
    except Exception as e:
        logger.error(f"AI问答失败: {e}")
        return AdminChatResponse(reply="AI服务暂时不可用，请稍后重试。")


@router.post("/admin-ai/recommend", response_model=RecommendResponse)
async def admin_recommend(request: RecommendRequest):
    """基于游客画像生成个性化营销话术"""
    try:
        system_prompt = RECOMMEND_PROMPT.format(
            nickname=request.nickname or "游客",
            tags="、".join(request.profile_tags) if request.profile_tags else "暂无标签",
            sentiment=request.sentiment_summary or "中性",
            consumption=request.consumption_summary or "暂无消费记录",
            snippet=request.conversation_snippet or "暂无近期对话",
        )
        response = await _get_client().chat.completions.create(
            model=settings.mimo_model,
            messages=[
                {"role": "system", "content": "你是景区营销专家，只返回推荐内容。"},
                {"role": "user", "content": system_prompt},
            ],
            temperature=0.8,
            max_tokens=600,
        )
        content = response.choices[0].message.content or "暂无推荐"
        return RecommendResponse(suggestions=content)
    except Exception as e:
        logger.error(f"推荐生成失败: {e}")
        return RecommendResponse(suggestions="推荐服务暂时不可用，请稍后重试。")
