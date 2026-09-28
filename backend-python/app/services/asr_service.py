import base64
import logging
import os

import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)


class ASRService:
    """MiMo ASR 语音识别服务

    调用 MiMo mimo-v2.5-asr 模型。
    消息格式：user 消息使用 input_audio 类型（base64 Data URL）。
    """

    ASR_MODEL = "mimo-v2.5-asr"

    async def transcribe(self, audio_bytes: bytes, audio_format: str = "wav") -> str:
        """将音频 bytes 转为文字"""
        if not audio_bytes:
            return ""

        # 编码为 Data URL
        audio_b64 = base64.b64encode(audio_bytes).decode()
        data_url = f"data:audio/{audio_format};base64,{audio_b64}"

        # 构建请求（OpenAI SDK 不直接支持 input_audio，用 httpx 手动发）
        request_body = {
            "model": self.ASR_MODEL,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_audio",
                            "input_audio": {
                                "data": data_url,
                                "format": audio_format,
                            },
                        }
                    ],
                }
            ],
        }

        # 禁用代理（避免系统代理阻断 MiMo API）
        proxy_keys = ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy']
        saved = {k: os.environ.pop(k, None) for k in proxy_keys}

        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                resp = await client.post(
                    f"{settings.mimo_base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {settings.mimo_api_key}",
                        "Content-Type": "application/json",
                    },
                    json=request_body,
                )

            if resp.status_code != 200:
                raise RuntimeError(f"ASR API 返回 {resp.status_code}: {resp.text[:200]}")

            data = resp.json()
            text = data["choices"][0]["message"]["content"] or ""
            return text.strip()

        except Exception as e:
            logger.error(f"MiMo ASR 识别失败: {e}")
            raise RuntimeError(f"ASR 识别失败: {str(e)}")

        finally:
            # 恢复代理设置
            for k, v in saved.items():
                if v is not None:
                    os.environ[k] = v


asr_service = ASRService()
