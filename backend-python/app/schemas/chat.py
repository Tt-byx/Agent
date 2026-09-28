from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, description="User message")
    session_id: str | None = Field(None, description="Session ID")


class ChatResponse(BaseModel):
    reply: str = Field(..., description="AI reply")
    session_id: str | None = Field(None, description="Session ID")
    audio: str | None = Field(None, description="base64 编码的 WAV 音频")
    expression: str = Field("Normal", description="数字人表情: Normal/Smile/Cry/Angry/Star")


class AudioChatRequest(BaseModel):
    session_id: str | None = Field(None, description="Session ID")
    audio_format: str = Field("webm", description="Audio format")
    sample_rate: int = Field(16000, description="Sample rate")
