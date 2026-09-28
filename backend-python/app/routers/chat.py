import asyncio
import base64
import json
import logging

from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from fastapi.responses import StreamingResponse, Response
from app.schemas.chat import ChatRequest, ChatResponse
from app.services.llm_service import chat_with_rag, chat_with_mimo, chat_stream
from app.services.asr_service import asr_service
from app.services.tts_service import tts_service
from app.services.rag_service import retrieve_context
from app.services.sentiment_service import analyze_sentiment
from app.utils.text_utils import strip_markdown

logger = logging.getLogger(__name__)
router = APIRouter()


async def detect_expression(text: str) -> str:
    """基于关键词的快速情感检测，返回 Live2D 表情名"""
    return _keyword_fallback_expression(text)


def _keyword_fallback_expression(text: str) -> str:
    """关键词表情检测（扩展版，覆盖更多情感场景）"""
    if not text:
        return "Normal"
    # 悲伤/道歉/遗憾
    if any(w in text for w in ["抱歉", "无法", "不知道", "没有找到", "暂时无法", "错误", "失败",
                                "遗憾", "对不起", "可惜", "伤心", "难过", "唉", "糟糕", "不幸",
                                "失望", "遗憾的是", "没法", "无法提供", "未能"]):
        return "Cry"
    # 惊讶/好奇
    if any(w in text for w in ["哇", "天哪", "居然", "竟然", "没想到", "真的吗", "哎呀",
                                "不可思议", "太神奇了", "难以置信", "惊讶", "惊奇",
                                "原来如此", "居然这样"]):
        return "Star"
    # 开心/赞美/祝福
    if any(w in text for w in ["恭喜", "太好了", "开心", "高兴", "快乐", "棒", "赞", "喜欢",
                                "美丽", "精彩", "欢迎", "祝", "感谢", "谢谢", "太棒了",
                                "非常好", "真不错", "哈哈", "嘿嘿", "幸福", "满意",
                                "舒服", "享受", "美妙", "壮观", "令人赞叹", "值得一看"]):
        return "Smile"
    # 警告/注意
    if any(w in text for w in ["注意", "小心", "警告", "禁止", "危险", "请勿", "不要",
                                "严禁", "当心", "务必", "切勿", "罚款", "违规"]):
        return "Angry"
    # 思考/分析
    if any(w in text for w in ["想想", "思考", "让我", "嗯", "分析", "研究", "考虑",
                                "其实", "我觉得", "根据", "综合来看"]):
        return "Circle"
    # 疑问（放在最后，因为很多回复都会包含问句）
    if any(w in text for w in ["？", "吗", "呢", "什么", "怎么", "为什么", "请问", "好奇"]):
        return "Star"
    return "Normal"  # 默认中性（不再默认微笑，避免所有回复都是笑）


# ??????????????????????????????????????????????
# TTS ??????
# ??????????????????????????????????????????????

@router.get("/tts/voices")
async def list_voices():
    """??????? TTS ??????"""
    return {
        "current": tts_service.voice,
        "voices": [
            {"id": vid, "name": v["name"], "gender": v["gender"], "style": v["style"]}
            for vid, v in tts_service.AVAILABLE_VOICES.items()
        ]
    }

@router.post("/tts/voice")
async def set_voice(voice_id: str = Form(...)):
    """?? TTS ????"""
    if voice_id not in tts_service.AVAILABLE_VOICES:
        raise HTTPException(status_code=400, detail=f"????: {voice_id}???: {list(tts_service.AVAILABLE_VOICES.keys())}")
    tts_service.voice = voice_id
    voice_info = tts_service.AVAILABLE_VOICES[voice_id]
    logger.info(f"TTS ?????: {voice_info['name']} ({voice_id})")
    return {"voice": voice_id, "name": voice_info["name"], "message": f"????{voice_info['name']}"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """文字聊天接口（RAG + TTS 按句合成 + 情感检测）"""
    # ??????
    user_emotion = "neutral"
    try:
        sentiment_result = await analyze_sentiment(request.message)
        user_emotion = sentiment_result.get("emotion", "neutral")
        logger.info(f"????: {user_emotion}")
    except Exception as e:
        logger.warning(f"????????: {e}")

    try:
        reply = await chat_with_rag(request.message, user_emotion)
    except Exception:
        try:
            reply = await chat_with_mimo(request.message, user_emotion)
        except Exception as e2:
            raise HTTPException(status_code=500, detail=f"AI服务调用失败: {str(e2)}")

    # 情感检测
    expression = await detect_expression(reply)

    # TTS 并行合成（非流式端点），剥离 Markdown 符号便于朗读
    audio_list = []
    try:
        sentences = _split_sentences(reply)
        clean_sentences = [strip_markdown(s) for s in sentences]
        clean_sentences = [s.strip() for s in clean_sentences if s.strip()]
        if clean_sentences:
            tts_tasks = [tts_service.synthesize(s) for s in clean_sentences]
            audio_results = await asyncio.gather(*tts_tasks, return_exceptions=True)
            for result in audio_results:
                if isinstance(result, Exception):
                    logger.warning(f"句子 TTS 失败: {result}")
                elif result:
                    audio_list.append(base64.b64encode(result).decode())
    except Exception as e:
        logger.warning(f"TTS 合成失败（不影响文字回复）: {e}")

    # 将多个音频用逗号连接（前端逐个播放）
    audio_str = ",".join(audio_list) if audio_list else None

    return ChatResponse(
        reply=reply,
        session_id=request.session_id,
        audio=audio_str,
        expression=expression,
    )


def _split_sentences(text: str) -> list[str]:
    """按中文标点分句"""
    sentences = []
    current = ""
    for char in text:
        current += char
        if char in "。！？；\n":
            if current.strip():
                sentences.append(current.strip())
            current = ""
    if current.strip():
        sentences.append(current.strip())
    return sentences if sentences else [text]


@router.post("/asr")
async def asr_endpoint(audio: UploadFile = File(...)):
    """ASR: 语音转文字（MiMo ASR API）"""
    try:
        audio_bytes = await audio.read()
        audio_format = audio.filename.split('.')[-1] if audio.filename else "wav"
        text = await asr_service.transcribe(audio_bytes, audio_format)
        return {"text": text, "format": audio_format}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"ASR error: {str(e)}")


@router.post("/tts")
async def tts_endpoint(
    text: str = Form(...),
    voice: str = Form(None),
):
    """TTS: 文字转语音"""
    try:
        audio_bytes = await tts_service.synthesize(text, voice=voice)
        return Response(
            content=audio_bytes,
            media_type="audio/mpeg",
            headers={"Content-Disposition": "attachment; filename=tts_output.mp3"}
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"TTS error: {str(e)}")


@router.post("/chat/stream")
async def chat_stream_endpoint(
    message: str = Form(None),
    audio: UploadFile = File(None),
    session_id: str = Form(None),
):
    """流式对话接口（支持文字和语音输入，流式输出文字+语音+表情）"""
    async def event_generator():
        input_text = message
        full_text = ""

        # 全局超时保护：最多 180 秒，防止任何原因导致永久卡住
        deadline = asyncio.get_event_loop().time() + 180

        def check_deadline():
            if asyncio.get_event_loop().time() > deadline:
                logger.error("[chat/stream] 全局超时 180s，强制结束")
                return True
            return False

        # 如果有音频输入，先 ASR 识别
        if audio is not None:
            try:
                audio_bytes = await audio.read()
                audio_format = audio.filename.split('.')[-1] if audio.filename else "webm"
                input_text = await asr_service.transcribe(audio_bytes, audio_format)
                yield _sse_event("asr_result", {"text": input_text})
            except Exception as e:
                logger.error(f"ASR failed: {e}")
                yield _sse_event("error", {"message": f"ASR 识别失败: {str(e)}"})
                yield _sse_event("done", {"session_id": session_id, "total_text": ""})
                return

        if not input_text or not input_text.strip():
            yield _sse_event("error", {"message": "输入为空"})
            yield _sse_event("done", {"session_id": session_id, "total_text": ""})
            return

        # ── 优化：情感分析 + RAG 并行执行 ──
        sentiment_task = asyncio.create_task(analyze_sentiment(input_text))
        rag_task = asyncio.create_task(retrieve_context(input_text))

        sentiment_result, context_str = await asyncio.gather(sentiment_task, rag_task)
        user_emotion = sentiment_result.get("emotion", "neutral") if sentiment_result else "neutral"

        # 构建上下文
        context_chunks = []
        if context_str:
            context_chunks = [context_str]
        if user_emotion and user_emotion != "neutral":
            context_chunks = [f"[用户当前情绪: {user_emotion}]"] + context_chunks

        # ── 优化：TTS 异步队列，不阻塞 LLM 流读取 ──
        audio_queue = asyncio.Queue()

        async def tts_worker(sentence: str, idx: int):
            """后台 TTS 任务：合成音频并放入队列（带超时保护）"""
            try:
                clean = strip_markdown(sentence.strip())
                if clean:
                    # 给 TTS 加 15 秒超时，防止网络问题导致永久卡住
                    audio_bytes = await asyncio.wait_for(
                        tts_service.synthesize(clean), timeout=15.0
                    )
                    if audio_bytes:
                        audio_b64 = base64.b64encode(audio_bytes).decode()
                        await audio_queue.put((idx, audio_b64))
                    else:
                        await audio_queue.put((idx, None))
                else:
                    await audio_queue.put((idx, None))
            except asyncio.TimeoutError:
                logger.warning(f"TTS timeout for sentence {idx}: {sentence[:30]}")
                await audio_queue.put((idx, None))
            except Exception as e:
                logger.warning(f"TTS failed for sentence {idx}: {e}")
                await audio_queue.put((idx, None))

        # 流式生成回答，TTS 异步执行不阻塞
        sentence_buffer = ""
        end_marks = set("。！？；\n")
        first_expression_sent = False
        tts_counter = 0
        tts_tasks = []

        # 用 asyncio.Event 通知消费者：所有任务已创建完毕
        all_sentences_queued = asyncio.Event()

        async def llm_producer():
            """LLM 生产者：读取流式 token，文本直接 yield，句子交给 TTS 后台"""
            nonlocal sentence_buffer, first_expression_sent, tts_counter, full_text
            try:
                async for text_chunk in chat_stream(input_text, context_chunks):
                    sentence_buffer += text_chunk
                    full_text += text_chunk
                    await audio_queue.put(("text", text_chunk))

                    # 遇到句号等标点，将句子交给后台 TTS 并检测表情（不阻塞）
                    if sentence_buffer and sentence_buffer[-1] in end_marks:
                        sentence = sentence_buffer.strip()
                        if sentence:
                            # 每句话都检测表情（核心改进：表情随内容变化）
                            expr = _keyword_fallback_expression(sentence)
                            first_expression_sent = True
                            await audio_queue.put(("expression", expr))
                            task = asyncio.create_task(tts_worker(sentence, tts_counter))
                            tts_tasks.append(task)
                            tts_counter += 1
                        sentence_buffer = ""
            except Exception as e:
                logger.error(f"LLM failed: {e}")
                await audio_queue.put(("error", str(e)))
            finally:
                # LLM 流结束后通知消费者
                all_sentences_queued.set()

        # 启动 LLM 生产者（在后台运行）
        llm_task = asyncio.create_task(llm_producer())

        # 消费者：从队列中取出结果并 yield
        tts_done_count = 0
        while True:
            try:
                item = await asyncio.wait_for(audio_queue.get(), timeout=0.1)
            except asyncio.TimeoutError:
                # 退出条件：LLM完成 + 所有句子已入队 + 所有TTS结果已处理 + 队列空
                if (llm_task.done() and all_sentences_queued.is_set()
                        and audio_queue.empty() and tts_done_count >= tts_counter):
                    break
                # 全局超时保护
                if check_deadline():
                    if not llm_task.done():
                        llm_task.cancel()
                    break
                continue

            if item[0] == "text":
                yield _sse_event("text_chunk", {"text": item[1]})
            elif item[0] == "expression":
                yield _sse_event("expression", {"expression": item[1]})
            elif item[0] == "error":
                yield _sse_event("error", {"message": f"AI error: {item[1]}"})
            else:
                # TTS 结果 (idx, audio_b64)
                _, audio_b64 = item
                tts_done_count += 1
                if audio_b64:
                    yield _sse_event("audio_chunk", {"audio": audio_b64, "format": "mp3"})

        # 等待所有 TTS 任务完成（带超时保护，最多等20秒）
        if tts_tasks:
            try:
                await asyncio.wait_for(
                    asyncio.gather(*tts_tasks, return_exceptions=True),
                    timeout=20.0
                )
            except asyncio.TimeoutError:
                logger.warning(f"TTS gather timeout, {len(tts_tasks)} tasks")
            # 收集队列中剩余的 TTS 结果
            while not audio_queue.empty():
                item = audio_queue.get_nowait()
                if isinstance(item, tuple) and len(item) == 2 and item[0] not in ("text", "expression", "error"):
                    _, audio_b64 = item
                    if audio_b64:
                        yield _sse_event("audio_chunk", {"audio": audio_b64, "format": "mp3"})

        # 如果有剩余未完结的文本，也发送表情和TTS（带超时）
        remaining = sentence_buffer.strip()
        if remaining:
            expr = _keyword_fallback_expression(remaining)
            yield _sse_event("expression", {"expression": expr})
            try:
                clean_remaining = strip_markdown(remaining)
                if clean_remaining:
                    audio_bytes = await asyncio.wait_for(
                        tts_service.synthesize(clean_remaining), timeout=15.0
                    )
                    if audio_bytes:
                        audio_b64 = base64.b64encode(audio_bytes).decode()
                        yield _sse_event("audio_chunk", {"audio": audio_b64, "format": "mp3"})
            except asyncio.TimeoutError:
                logger.warning("TTS final timeout")
            except Exception as e:
                logger.warning(f"TTS final failed: {e}")
        elif not first_expression_sent:
            # 极短回复（没有完整句子），发送默认表情
            yield _sse_event("expression", {"expression": _keyword_fallback_expression(full_text)})

        yield _sse_event("done", {
            "session_id": session_id,
            "total_text": full_text,
        })
        logger.info(f"[chat/stream] 完成: {len(full_text)}字, {tts_counter}句TTS")

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream; charset=utf-8",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


def _sse_event(event_type: str, data: dict) -> str:
    return f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
