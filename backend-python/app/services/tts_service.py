import asyncio
import logging

import edge_tts

logger = logging.getLogger(__name__)


class TTSService:
    """edge-tts 本地语音合成服务

    使用 Microsoft Edge TTS 引擎，延迟仅 0.3-0.5s/句。
    输出 MP3 格式，前端 Web Audio API 原生支持。
    """

    # 默认中文语音：晓晓（女声，自然度高）
    DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    # 可用中文音色列表
    AVAILABLE_VOICES = {
        "zh-CN-XiaoxiaoNeural": {"name": "晓晓", "gender": "女", "style": "温和自然"},
        "zh-CN-YunxiNeural": {"name": "云希", "gender": "男", "style": "阳光活力"},
        "zh-CN-YunyangNeural": {"name": "云扬", "gender": "男", "style": "专业沉稳"},
        "zh-CN-XiaoyiNeural": {"name": "晓艺", "gender": "女", "style": "活泼可爱"},
        "zh-CN-YunjianNeural": {"name": "云健", "gender": "男", "style": "成熟磁性"},
        "zh-CN-XiaochenNeural": {"name": "晓辰", "gender": "女", "style": "知性优雅"},
    }

    def __init__(self):
        self._current_voice = self.DEFAULT_VOICE

    @property
    def voice(self):
        return self._current_voice

    @voice.setter
    def voice(self, value: str):
        if value in self.AVAILABLE_VOICES:
            self._current_voice = value
        else:
            logger.warning(f"未知音色 {value}，使用默认音色")

    async def synthesize(self, text: str, voice: str = None) -> bytes:
        """将文字合成为 MP3 音频 bytes"""
        if not text or not text.strip():
            return b""

        use_voice = voice if voice and voice in self.AVAILABLE_VOICES else self._current_voice

        try:
            communicate = edge_tts.Communicate(text.strip(), use_voice)
            audio_data = bytearray()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_data.extend(chunk["data"])
            return bytes(audio_data) if audio_data else b""
        except Exception as e:
            logger.error(f"edge-tts 合成失败: {e}")
            raise RuntimeError(f"TTS 合成失败: {str(e)}")


tts_service = TTSService()
