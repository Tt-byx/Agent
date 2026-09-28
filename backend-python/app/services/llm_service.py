import logging
from openai import AsyncOpenAI
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)
_client = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(http_client=httpx.AsyncClient(trust_env=False),
            api_key=settings.mimo_api_key or "not-set",
            base_url=settings.mimo_base_url,
        )
    return _client


SYSTEM_PROMPT = """你是一个专业的景区导游AI数字人助手。你的职责是为游客提供关于景区的准确、友好的信息和建议。

【回答格式要求——必须严格遵守】
1. 禁止使用任何 Markdown 格式符号：不要用 **、*、#、-、1.、```、>、| 等符号。
2. 用纯文本、口语化的方式回答，就像一个真人导游在面对面跟游客聊天。
3. 如果需要列举，用"第一、第二"或"首先、其次"等自然语言连接。
4. 回答要简洁，控制在3-5句话，避免长篇大论。
5. 用中文回答。"""




# ── 用户情绪→回答风格引导 ──
EMOTION_STYLE_GUIDE = {
    "happy": "用户心情很好，回答时用更热情、活泼的语气，多用感叹句，配合用户的开心情绪",
    "excited": "用户很兴奋，回答时语气要同样振奋，多分享有趣的信息，用感叹号表达热情",
    "grateful": "用户在表达感谢，回答时语气温暖亲切，表示很高兴能帮到他",
    "surprised": "用户很惊讶，回答时可以用\"确实\"\"没错\"等确认语气，再补充有趣的信息",
    "confused": "用户有些困惑，回答时要更耐心、条理清晰，用简单易懂的语言解释，分步骤说明",
    "angry": "用户可能不满，回答时语气要平和、真诚，先表示理解他的感受，再提供解决方案",
    "worried": "用户有些担心，回答时语气要安慰、安抚，提供明确的信息消除顾虑",
    "sad": "用户心情不好，回答时语气要温柔、关怀，适当安慰并提供帮助",
}


def _build_system_prompt(user_emotion: str = None) -> str:
    """根据用户情绪构建系统提示"""
    prompt = SYSTEM_PROMPT
    if user_emotion and user_emotion in EMOTION_STYLE_GUIDE:
        prompt += f"\n\n当前情境：{EMOTION_STYLE_GUIDE[user_emotion]}"
    return prompt

async def chat_with_mimo(message: str, user_emotion: str = None) -> str:
    """调用 MiMo 大模型 API — 无知识库版本（fallback）"""
    response = await _get_client().chat.completions.create(
        model=settings.mimo_model,
        messages=[
            {"role": "system", "content": _build_system_prompt(user_emotion)},
            {"role": "user", "content": message},
        ],
        temperature=0.7,
        max_tokens=512,
    )
    msg = response.choices[0].message
    content = msg.content or ""
    if not content.strip():
        logger.warning("[chat_with_mimo] 返回空内容，重试一次")
        response = await _get_client().chat.completions.create(
            model=settings.mimo_model,
            messages=[
                {"role": "system", "content": _build_system_prompt(user_emotion)},
                {"role": "user", "content": message},
            ],
            temperature=0.8,
            max_tokens=512,
        )
        content = response.choices[0].message.content or ""
    return content


async def chat_with_rag(message: str, user_emotion: str = None) -> str:
    """RAG 增强对话：检索知识库 → 拼接上下文 → 调用大模型"""
    from app.services.rag_service import retrieve_context, build_rag_prompt

    context = await retrieve_context(message)
    messages = build_rag_prompt(context, message)

    response = await _get_client().chat.completions.create(
        model=settings.mimo_model,
        messages=messages,
        temperature=0.7,
        max_tokens=512,
    )
    msg = response.choices[0].message
    content = msg.content or ""
    if not content.strip():
        logger.warning("[chat_with_rag] 返回空内容，重试一次")
        response = await _get_client().chat.completions.create(
            model=settings.mimo_model,
            messages=messages,
            temperature=0.8,
            max_tokens=512,
        )
        content = response.choices[0].message.content or ""
    return content


async def chat_stream(message: str, context_chunks: list[str] = None, user_emotion: str = None):
    """流式对话 — 供 /api/chat/stream 使用"""
    from app.services.rag_service import build_rag_prompt

    if context_chunks:
        context = "\n\n".join(context_chunks)
        messages = build_rag_prompt(context, message)
    else:
        messages = [
            {"role": "system", "content": _build_system_prompt(user_emotion)},
            {"role": "user", "content": message},
        ]

    response = await _get_client().chat.completions.create(
        model=settings.mimo_model,
        messages=messages,
        temperature=0.7,
        max_tokens=512,
        stream=True,
        stop=["\n\n\n"],
    )

    content_yielded = False
    reasoning_text = ""

    async for chunk in response:
        if chunk.choices:
            delta = chunk.choices[0].delta
            # 正式回答内容
            text = delta.content
            if text:
                content_yielded = True
                yield text
            # 收集 reasoning_content（模型思考过程），作为 fallback
            reasoning = getattr(delta, 'reasoning_content', None)
            if reasoning:
                reasoning_text += reasoning

    # Fallback：如果正式内容为空但有思考内容，尝试用非流式重新请求
    if not content_yielded:
        logger.warning(f"[chat_stream] 流式返回0字，reasoning={len(reasoning_text)}字，尝试非流式fallback")
        try:
            fallback_resp = await _get_client().chat.completions.create(
                model=settings.mimo_model,
                messages=messages,
                temperature=0.7,
                max_tokens=512,
                stop=["\n\n\n"],
            )
            fallback_text = fallback_resp.choices[0].message.content or ""
            if fallback_text.strip():
                logger.info(f"[chat_stream] fallback成功: {fallback_text[:50]}")
                yield fallback_text
            else:
                logger.error(f"[chat_stream] fallback也返回空")
        except Exception as e:
            logger.error(f"[chat_stream] fallback失败: {e}")
