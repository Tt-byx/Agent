"""音频分析模块 — 提取音量包络、检测语音段、估算语速

零外部依赖（仅 numpy + 标准库）。
"""
from __future__ import annotations

import io
import logging
import struct
import wave
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

TARGET_SR = 16_000
FRAME_MS = 20
FRAME_SAMPLES = int(TARGET_SR * FRAME_MS / 1000)  # 320


def analyze(
    audio_path: str,
    *,
    target_sr: int = TARGET_SR,
    frame_ms: int = FRAME_MS,
) -> dict:
    """分析音频文件，返回 {volume_envelope, times, duration, sr, speech_segments}"""
    sr, data = _load(audio_path)
    if data.size == 0:
        return _empty(target_sr, frame_ms)

    if data.ndim > 1:
        data = data.mean(axis=1)
    data = data.astype(np.float32)
    peak = np.max(np.abs(data)) or 1.0
    data /= peak

    if sr != target_sr:
        ratio = target_sr / sr
        new_len = int(len(data) * ratio)
        x_old = np.linspace(0, 1, len(data))
        x_new = np.linspace(0, 1, new_len)
        data = np.interp(x_new, x_old, data).astype(np.float32)
        sr = target_sr

    hop = int(sr * frame_ms / 1000)
    n_frames = max(1, len(data) // hop)
    envelope = np.zeros(n_frames, dtype=np.float32)
    for i in range(n_frames):
        chunk = data[i * hop : (i + 1) * hop]
        envelope[i] = float(np.sqrt(np.mean(chunk ** 2)))

    mx = float(np.max(envelope)) or 1.0
    envelope = (envelope / mx).astype(np.float32)

    # 低通平滑
    alpha = 0.3
    smoothed = np.empty_like(envelope)
    smoothed[0] = envelope[0]
    for i in range(1, len(envelope)):
        smoothed[i] = alpha * envelope[i] + (1 - alpha) * smoothed[i - 1]
    envelope = smoothed

    times = np.arange(n_frames) * frame_ms / 1000.0
    duration = len(data) / sr

    return {
        "volume_envelope": envelope,
        "times": times,
        "duration": float(duration),
        "sr": sr,
        "speech_segments": detect_speech(envelope, times),
        "frame_ms": frame_ms,
    }


def from_bytes(audio_bytes: bytes, fmt: str = "wav") -> dict:
    """从内存字节分析音频"""
    import tempfile, os
    with tempfile.NamedTemporaryFile(suffix=f".{fmt}", delete=False) as f:
        f.write(audio_bytes)
        tmp = f.name
    try:
        return analyze(tmp)
    finally:
        os.unlink(tmp)


def detect_speech(
    envelope: np.ndarray,
    times: np.ndarray,
    threshold: float = 0.05,
    min_dur: float = 0.15,
) -> list[dict]:
    """检测语音段"""
    mask = envelope > threshold
    segments: list[dict] = []
    start = None
    for i, m in enumerate(mask):
        if m and start is None:
            start = i
        elif not m and start is not None:
            if times[i] - times[start] >= min_dur:
                segments.append({"start": float(times[start]), "end": float(times[i])})
            start = None
    if start is not None and times[-1] - times[start] >= min_dur:
        segments.append({"start": float(times[start]), "end": float(times[-1])})
    return segments


def estimate_wpm(segments: list[dict], text_len: int) -> float:
    """估算语速"""
    dur = sum(s["end"] - s["start"] for s in segments)
    if dur < 0.1 or text_len < 1:
        return 150.0
    return text_len / (dur / 60)


# ── 内部工具 ──────────────────────────────────────────────


def _load(path: str):
    """加载音频文件 → (sample_rate, int16 ndarray)"""
    try:
        import soundfile as sf
        data, sr = sf.read(path, dtype="int16")
        return sr, np.asarray(data, dtype=np.int16)
    except ImportError:
        pass
    try:
        with wave.open(path, "rb") as wf:
            sr = wf.getframerate()
            n = wf.getnframes()
            raw = wf.readframes(n)
            data = np.frombuffer(raw, dtype=np.int16)
            if wf.getnchannels() > 1:
                data = data.reshape(-1, wf.getnchannels())
            return sr, data
    except Exception:
        pass
    try:
        with open(path, "rb") as f:
            raw = f.read()
        data = np.frombuffer(raw, dtype=np.int16)
        return 16_000, data
    except Exception as e:
        logger.error("无法加载音频 %s: %s", path, e)
        return 16_000, np.array([], dtype=np.int16)


def _empty(sr, ms):
    return {
        "volume_envelope": np.array([0.0], dtype=np.float32),
        "times": np.array([0.0]),
        "duration": 0.0,
        "sr": sr,
        "speech_segments": [],
        "frame_ms": ms,
    }
