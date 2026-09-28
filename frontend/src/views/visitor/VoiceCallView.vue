<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import Live2DCanvas from '@/components/live2d/Live2DCanvas.vue'

const router = useRouter()
const chatStore = useChatStore()
const userStore = useUserStore()
const live2dRef = ref(null)

// 通话状态: connecting → idle → listening → processing → speaking
const callState = ref('connecting')
const statusText = ref('正在连接...')
const autoRecord = ref(false) // 播放完后自动录音

// 录音
let mediaRecorder = null
let audioChunks = []
let audioContext = null
let analyser = null
let animFrame = 0
const volumeLevel = ref(0)

// 口型同步
let _mouthRAF = 0

// speaking 兜底超时
let _speakingTimer = null

// ── AudioBuffer → WAV Blob ──
function encodeWav(audioBuffer) {
  const numChannels = audioBuffer.numberOfChannels
  const sampleRate = audioBuffer.sampleRate
  const format = 1 // PCM
  const bitsPerSample = 16

  // 交织多声道数据
  let interleaved
  if (numChannels === 1) {
    interleaved = audioBuffer.getChannelData(0)
  } else {
    const ch0 = audioBuffer.getChannelData(0)
    const ch1 = audioBuffer.getChannelData(1)
    interleaved = new Float32Array(ch0.length * 2)
    for (let i = 0, j = 0; i < ch0.length; i++) {
      interleaved[j++] = ch0[i]
      interleaved[j++] = ch1[i]
    }
  }

  // Float32 → Int16
  const dataLength = interleaved.length * 2
  const buffer = new ArrayBuffer(44 + dataLength)
  const view = new DataView(buffer)

  // WAV header
  writeString(view, 0, 'RIFF')
  view.setUint32(4, 36 + dataLength, true)
  writeString(view, 8, 'WAVE')
  writeString(view, 12, 'fmt ')
  view.setUint32(16, 16, true) // chunk size
  view.setUint16(20, format, true)
  view.setUint16(22, numChannels, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * numChannels * bitsPerSample / 8, true)
  view.setUint16(32, numChannels * bitsPerSample / 8, true)
  view.setUint16(34, bitsPerSample, true)
  writeString(view, 36, 'data')
  view.setUint32(40, dataLength, true)

  // PCM data
  let offset = 44
  for (let i = 0; i < interleaved.length; i++) {
    const s = Math.max(-1, Math.min(1, interleaved[i]))
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
    offset += 2
  }

  return new Blob([buffer], { type: 'audio/wav' })
}

function writeString(view, offset, str) {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i))
  }
}

// ── 录音 ──
async function startRecording() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    audioContext = new AudioContext()
    const source = audioContext.createMediaStreamSource(stream)
    analyser = audioContext.createAnalyser()
    analyser.fftSize = 256
    source.connect(analyser)

    mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' })
    audioChunks = []

    mediaRecorder.ondataavailable = (e) => {
      if (e.data.size > 0) audioChunks.push(e.data)
    }

    mediaRecorder.onstop = async () => {
      stream.getTracks().forEach(t => t.stop())
      cancelAnimationFrame(animFrame)
      volumeLevel.value = 0

      if (audioChunks.length === 0) {
        if (audioContext) { audioContext.close(); audioContext = null }
        callState.value = 'idle'
        statusText.value = '点击开始对话'
        return
      }

      const webmBlob = new Blob(audioChunks, { type: 'audio/webm' })
      if (webmBlob.size < 1000) {
        if (audioContext) { audioContext.close(); audioContext = null }
        callState.value = 'idle'
        statusText.value = '点击开始对话'
        return
      }

      // webm → wav（MiMo ASR 只支持 wav/mp3）
      try {
        // 复用已有的 audioContext 来解码
        const arrayBuf = await webmBlob.arrayBuffer()
        const decoded = await audioContext.decodeAudioData(arrayBuf)
        const wavBlob = encodeWav(decoded)
        if (audioContext) { audioContext.close(); audioContext = null }

        callState.value = 'processing'
        statusText.value = 'AI 思考中...'
        chatStore.sendVoiceMessage(wavBlob, 'wav')
      } catch (e) {
        console.error('音频转码失败:', e)
        if (audioContext) { audioContext.close(); audioContext = null }
        callState.value = 'idle'
        statusText.value = '录音转码失败，请重试'
      }
    }

    mediaRecorder.start(100)
    callState.value = 'listening'
    statusText.value = '正在聆听...'

    // 音量可视化
    const dataArray = new Uint8Array(analyser.frequencyBinCount)
    const tick = () => {
      if (!analyser) return
      analyser.getByteFrequencyData(dataArray)
      let sum = 0
      for (let i = 0; i < dataArray.length; i++) sum += dataArray[i]
      volumeLevel.value = Math.min(1, (sum / dataArray.length) / 128)
      animFrame = requestAnimationFrame(tick)
    }
    animFrame = requestAnimationFrame(tick)
  } catch (e) {
    console.error('录音失败:', e)
    statusText.value = '麦克风权限被拒绝'
  }
}

function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
}

// ── 核心状态转移：loading 变为 false 时 ──
// loading 为 false 有两种情况：
//   A) onDone 正常结束 → 检查 audioPlaying
//   B) 出错结束 → audioPlaying 为 false
watch(() => chatStore.loading, (loading, oldLoading) => {
  if (oldLoading && !loading && callState.value === 'processing') {
    if (chatStore.audioPlaying) {
      // 有音频在播放 → 进入 speaking
      callState.value = 'speaking'
      statusText.value = 'AI 正在回答...'
      // 兜底超时：30 秒后如果还没结束，强制回到 idle
      _speakingTimer = setTimeout(() => {
        if (callState.value === 'speaking') {
          chatStore.audioPlayer.stop()
          chatStore.audioPlaying = false
          goToIdle('AI 回答完毕')
        }
      }, 30000)
    } else {
      // 无音频（出错或 TTS 未生成）→ 直接 idle
      const lastMsg = chatStore.messages[chatStore.messages.length - 1]
      if (lastMsg?.isError) {
        statusText.value = 'AI 出错了'
        setTimeout(() => goToIdle('点击开始对话'), 2000)
      } else {
        goToIdle('点击开始对话')
      }
    }
  }
})

// ── audioPlaying 变为 false 时退出 speaking ──
watch(() => chatStore.audioPlaying, (playing, oldPlaying) => {
  if (oldPlaying && !playing && callState.value === 'speaking') {
    if (_speakingTimer) { clearTimeout(_speakingTimer); _speakingTimer = null }
    goToIdleWithAutoRecord()
  }
})

// ── 错误兜底：processing 中出错 ──
watch(() => chatStore.messages, (msgs) => {
  if (msgs.length > 0) {
    const last = msgs[msgs.length - 1]
    if (last.isError && callState.value === 'processing') {
      callState.value = 'idle'
      statusText.value = 'AI 出错了，请重试'
      setTimeout(() => { statusText.value = '点击开始对话' }, 3000)
    }
  }
}, { deep: true })

// ── 口型同步（改为音频驱动，替代正弦波） ──
watch(() => chatStore.audioPlaying, (playing) => {
  if (_mouthRAF) { cancelAnimationFrame(_mouthRAF); _mouthRAF = 0 }
  if (playing && live2dRef.value) {
    // 优先使用 AudioPlayer 的 AnalyserNode 驱动口型（真实音频分析）
    const analyser = chatStore.audioPlayer?.analyser
    if (analyser) {
      live2dRef.value.startLipSyncFromAnalyser(analyser)
    } else {
      // AnalyserNode 不可用时，回退到简单正弦波
      const tick = () => {
        if (!live2dRef.value || !chatStore.audioPlaying) {
          live2dRef.value?.setLipSync(0)
          _mouthRAF = 0
          return
        }
        const open = 0.1 + 0.9 * Math.abs(Math.sin(performance.now() / 1000 * 6))
        live2dRef.value.setLipSync(open)
        _mouthRAF = requestAnimationFrame(tick)
      }
      _mouthRAF = requestAnimationFrame(tick)
    }
  } else {
    live2dRef.value?.stopLipSync()
  }
})

// ── 数字人表情驱动 ──
watch(() => chatStore.currentExpression, (expr) => {
  if (expr && live2dRef.value) {
    live2dRef.value.setExpression(expr)
  }
})

// 状态变化时控制表情：进入 processing 时重置，进入 idle 时恢复
watch(() => callState.value, (state) => {
  if (state === 'processing') {
    // 新一轮对话开始：立即重置上轮残留表情
    live2dRef.value?.setExpression('Normal')
    chatStore.currentExpression = 'Normal'
  } else if (state === 'idle') {
    // 对话结束：恢复默认表情
    live2dRef.value?.setExpression('Normal')
    chatStore.currentExpression = 'Normal'
  }
})

// ── 状态转移辅助函数 ──
function goToIdle(text) {
  if (_speakingTimer) { clearTimeout(_speakingTimer); _speakingTimer = null }
  callState.value = 'idle'
  statusText.value = text || '点击开始对话'
}

function goToIdleWithAutoRecord() {
  // speaking 结束后，如果 autoRecord 开启则自动录音
  if (autoRecord.value) {
    setTimeout(() => {
      if (callState.value === 'idle') startRecording()
    }, 500)
  } else {
    goToIdle()
  }
}

function endCall() {
  autoRecord.value = false
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  if (_mouthRAF) { cancelAnimationFrame(_mouthRAF); _mouthRAF = 0 }
  if (_speakingTimer) { clearTimeout(_speakingTimer); _speakingTimer = null }
  live2dRef.value?.setLipSync(0)
  chatStore.audioPlayer.stop()
  chatStore.audioPlaying = false
  callState.value = 'idle'
  router.push('/chat')
}

onMounted(async () => {
  await userStore.fetchMe()
  chatStore.initWebSocket()

  const waitForWs = () => new Promise((resolve) => {
    if (chatStore.isWsConnected) return resolve()
    const unwatch = watch(() => chatStore.isWsConnected, (connected) => {
      if (connected) { unwatch(); resolve() }
    })
    setTimeout(() => { unwatch(); resolve() }, 5000)
  })
  await waitForWs()
  callState.value = 'idle'
  statusText.value = '点击开始对话'
})

onUnmounted(() => {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') mediaRecorder.stop()
  if (_mouthRAF) { cancelAnimationFrame(_mouthRAF); _mouthRAF = 0 }
  if (_speakingTimer) { clearTimeout(_speakingTimer); _speakingTimer = null }
})
</script>

<template>
  <div class="voice-call">
    <div class="avatar-area">
      <Live2DCanvas ref="live2dRef" class="live2d" />
    </div>

    <div class="status-bar" :class="callState">
      <div class="status-dot" :class="callState"></div>
      <span>{{ statusText }}</span>
    </div>

    <div class="volume-ring" :style="{ transform: `scale(${1 + volumeLevel * 0.5})`, opacity: 0.3 + volumeLevel * 0.7 }">
      <div class="ring-inner"></div>
    </div>

    <div class="controls">
      <!-- idle / speaking → 点击录音 -->
      <button
        v-if="callState === 'idle' || callState === 'speaking'"
        class="call-btn start"
        @click="startRecording"
      >
        <span class="btn-icon">🎤</span>
        <span>按住说话</span>
      </button>

      <!-- listening → 松手发送 -->
      <button
        v-else-if="callState === 'listening'"
        class="call-btn listening"
        @mousedown.prevent="stopRecording"
        @touchstart.prevent="stopRecording"
      >
        <span class="btn-icon pulse">⏺</span>
        <span>松手发送</span>
      </button>

      <!-- processing → 等待 -->
      <button
        v-else-if="callState === 'processing'"
        class="call-btn processing"
        disabled
      >
        <span class="btn-icon spin">⏳</span>
        <span>思考中...</span>
      </button>

      <!-- connecting -->
      <button
        v-else-if="callState === 'connecting'"
        class="call-btn processing"
        disabled
      >
        <span class="btn-icon spin">⏳</span>
        <span>连接中...</span>
      </button>

      <button class="end-btn" @click="endCall">
        <el-icon :size="18"><Close /></el-icon>
        结束通话
      </button>
    </div>
  </div>
</template>

<style scoped>
.voice-call {
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #e8f4f8 0%, #d4e9d7 40%, #f0ebe3 100%);
  gap: 20px;
  position: relative;
  overflow: hidden;
}

.avatar-area {
  flex: 1;
  width: 100%;
  max-width: 500px;
  min-height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.live2d { width: 100%; height: 100%; }

.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 14px;
  color: #5a6577;
  background: rgba(255,255,255,0.8);
  backdrop-filter: blur(8px);
}

.status-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #a0aec0;
}
.status-dot.listening { background: #5a8a6a; animation: pulse 1s ease infinite; }
.status-dot.processing { background: #c4956a; animation: pulse 0.8s ease infinite; }
.status-dot.speaking { background: #6a9fb5; }

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.3); }
}

.volume-ring {
  width: 120px; height: 120px; border-radius: 50%;
  border: 3px solid rgba(90,138,106,0.4);
  display: flex; align-items: center; justify-content: center;
  transition: transform 0.1s, opacity 0.1s;
  position: absolute; bottom: 160px;
}
.ring-inner { width: 80px; height: 80px; border-radius: 50%; background: rgba(90,138,106,0.1); }

.controls {
  display: flex; flex-direction: column; align-items: center;
  gap: 12px; padding-bottom: 32px;
}

.call-btn {
  display: flex; align-items: center; gap: 8px;
  padding: 16px 40px; border: none; border-radius: 50px;
  font-size: 16px; font-weight: 600; cursor: pointer;
  transition: all 0.2s; color: #fff; min-width: 200px; justify-content: center;
}
.call-btn.start { background: linear-gradient(135deg, #5a8a6a, #4d7a5e); box-shadow: 0 4px 20px rgba(90,138,106,0.4); }
.call-btn.start:hover { transform: scale(1.05); box-shadow: 0 6px 24px rgba(90,138,106,0.5); }
.call-btn.listening { background: linear-gradient(135deg, #c0705a, #a85a48); box-shadow: 0 4px 20px rgba(192,112,90,0.4); }
.call-btn.processing { background: #a0aec0; cursor: not-allowed; }

.btn-icon { font-size: 20px; }
.btn-icon.pulse { animation: pulse 0.8s ease infinite; }
.btn-icon.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.end-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 20px; border: 1px solid #d5d2cc; border-radius: 20px;
  background: rgba(255,255,255,0.6); color: #8d95a3; font-size: 13px;
  cursor: pointer; transition: all 0.2s;
}
.end-btn:hover { border-color: #c0705a; color: #c0705a; background: rgba(255,255,255,0.9); }
</style>
