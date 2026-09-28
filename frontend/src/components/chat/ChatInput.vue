<script setup>
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { VoiceRecorder } from './VoiceRecorder.js'
import { recognizeSpeech } from '@/api/chat'

const router = useRouter()
const emit = defineEmits(['sendText'])

const props = defineProps({
  loading: {
    type: Boolean,
    default: false,
  },
})

const inputText = ref('')
const isRecording = ref(false)
const isRecognizing = ref(false)
const recordingError = ref('')
const recordingDuration = ref(0)

const recorder = new VoiceRecorder()
let durationTimer = null

// --- 文字发送 ---
function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.loading) return
  emit('sendText', text)
  inputText.value = ''
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

// --- 语音录入 → ASR → 填入输入框 ---
async function toggleRecording() {
  if (props.loading || isRecognizing.value) return
  if (isRecording.value) {
    await stopAndRecognize()
  } else {
    await startRecording()
  }
}

async function startRecording() {
  recordingError.value = ''
  recordingDuration.value = 0

  try {
    await recorder.start()
    isRecording.value = true
    durationTimer = setInterval(() => {
      recordingDuration.value = Math.floor((Date.now() - recorder.startTime) / 1000)
    }, 200)
  } catch (err) {
    recordingError.value = err.message || '录音失败'
    setTimeout(() => { recordingError.value = '' }, 3000)
  }
}

async function stopAndRecognize() {
  isRecording.value = false
  if (durationTimer) {
    clearInterval(durationTimer)
    durationTimer = null
  }

  try {
    const result = await recorder.stop()
    if (!result || !result.blob || result.blob.size < 1000 || result.duration < 500) {
      recordingError.value = '录音时间太短，至少 0.5 秒'
      setTimeout(() => { recordingError.value = '' }, 2000)
      return
    }

    // 调用 ASR 识别
    isRecognizing.value = true
    const asrResult = await recognizeSpeech(result.blob, result.format)

    if (asrResult && asrResult.text && asrResult.text.trim()) {
      // 识别结果填入输入框
      inputText.value = asrResult.text.trim()
      // 聚焦输入框方便用户编辑
      const textarea = document.querySelector('.chat-input .el-textarea textarea')
      if (textarea) textarea.focus()
    } else {
      recordingError.value = '未识别到语音内容'
      setTimeout(() => { recordingError.value = '' }, 2000)
    }
  } catch (err) {
    recordingError.value = '语音识别失败: ' + (err.message || '未知错误')
    setTimeout(() => { recordingError.value = '' }, 3000)
  } finally {
    isRecognizing.value = false
  }
}

function formatDuration(seconds) {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}

onUnmounted(() => {
  recorder.destroy()
  if (durationTimer) clearInterval(durationTimer)
})
</script>

<template>
  <div class="chat-input">
    <!-- 文字输入框 -->
    <el-input
      v-model="inputText"
      type="textarea"
      :rows="2"
      placeholder="输入你的问题，或点击语音输入..."
      resize="none"
      :disabled="loading || isRecording || isRecognizing"
      @keydown="handleKeydown"
    />

    <!-- 按钮区域 -->
    <div class="button-group">
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!inputText.trim() || isRecording || isRecognizing"
        @click="handleSend"
      >
        发送
      </el-button>

      <el-button
        :type="isRecording ? 'danger' : 'success'"
        :disabled="loading || isRecognizing"
        @click="toggleRecording"
      >
        <el-icon v-if="!isRecording && !isRecognizing"><Microphone /></el-icon>
        <el-icon v-if="isRecognizing" class="is-loading"><Loading /></el-icon>
        <span v-if="isRecognizing">识别中...</span>
        <span v-else-if="isRecording">停止录音</span>
        <span v-else>语音输入</span>
      </el-button>

      <el-button
        type="info"
        plain
        :disabled="loading || isRecording"
        @click="router.push('/call')"
        class="call-mode-btn"
      >
        <el-icon><Phone /></el-icon>
        通话模式
      </el-button>
    </div>

    <!-- 录音状态 -->
    <div v-if="isRecording" class="recording-indicator">
      <div class="wave-animation">
        <span></span><span></span><span></span><span></span><span></span>
      </div>
      <span class="recording-label">录音中</span>
      <span class="duration">{{ formatDuration(recordingDuration) }}</span>
      <span class="recording-hint">点击「停止录音」进行识别</span>
    </div>

    <!-- 识别中状态 -->
    <div v-if="isRecognizing" class="recognizing-indicator">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在识别语音...</span>
    </div>

    <!-- 错误提示 -->
    <div v-if="recordingError" class="recording-error">
      {{ recordingError }}
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 16px 20px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.button-group {
  display: flex;
  gap: 10px;
  margin-top: 10px;
  align-items: center;
}

.button-group .el-button {
  height: 40px;
  min-width: 80px;
}

.recording-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding: 8px 12px;
  background: #fef0f0;
  border-radius: 8px;
  color: #f56c6c;
  font-size: 14px;
}

.recognizing-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 8px 12px;
  background: #fdf6ec;
  border-radius: 8px;
  color: #e6a23c;
  font-size: 14px;
}

.recording-label {
  font-weight: 600;
}

.duration {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.recording-hint {
  font-size: 12px;
  color: #e6a23c;
  margin-left: auto;
}

.wave-animation {
  display: flex;
  align-items: center;
  gap: 3px;
  height: 20px;
}

.wave-animation span {
  display: block;
  width: 3px;
  height: 8px;
  background: #f56c6c;
  border-radius: 2px;
  animation: wave 1s ease-in-out infinite;
}

.wave-animation span:nth-child(2) { animation-delay: 0.1s; }
.wave-animation span:nth-child(3) { animation-delay: 0.2s; }
.wave-animation span:nth-child(4) { animation-delay: 0.3s; }
.wave-animation span:nth-child(5) { animation-delay: 0.4s; }

@keyframes wave {
  0%, 100% { height: 8px; }
  50% { height: 20px; }
}

.recording-error {
  margin-top: 6px;
  padding: 4px 8px;
  color: #f56c6c;
  font-size: 12px;
  background: #fef0f0;
  border-radius: 4px;
}
</style>
