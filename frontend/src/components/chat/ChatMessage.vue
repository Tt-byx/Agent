<script setup>
import { ref, inject, computed } from 'vue'
import { submitFeedback } from '@/api/analytics'
import { useTtsStore } from '@/stores/tts'
import { ElMessage } from 'element-plus'

const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
})

// Pinia store：全局单例，确保同一时间只有一条 TTS 在播放
const ttsStore = useTtsStore()

// 从 ChatView 注入的当前音色
const currentVoice = inject('currentVoice', ref(''))

// 消息唯一标识：内容前 80 字 + 时间戳
const msgKey = computed(() =>
  `${(props.message.content || '').slice(0, 80)}_${props.message.timestamp || ''}`
)

// 是否正在播放（响应式）
const playing = computed(() => ttsStore.isPlaying(msgKey.value))

// 播放/重新播放
async function playTTS() {
  await ttsStore.play(props.message.content, currentVoice.value, msgKey.value)
}

const feedbackGiven = ref(null)

async function giveFeedback(type) {
  const msgId = props.message.id || Date.now()
  feedbackGiven.value = type
  ElMessage.success(type === 'like' ? '已点赞 👍' : '已点踩 👎')
  try {
    await submitFeedback(msgId, type)
  } catch {
    // ignore
  }
}

function formatTime(timestamp) {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const now = new Date()
  const diff = (now - date) / 1000

  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return date.toLocaleDateString()
}
</script>

<template>
  <div class="chat-message" :class="[message.role, { error: message.isError }]">
    <div class="avatar">
      <el-avatar v-if="message.role === 'user'" :size="36" style="background: #409eff">
        <el-icon><User /></el-icon>
      </el-avatar>
      <el-avatar v-else :size="36" style="background: #67c23a">
        <el-icon><ChatDotRound /></el-icon>
      </el-avatar>
    </div>
    <div class="content-wrapper">
      <div class="bubble" :class="message.role">
        <!-- 语音消息标识 -->
        <span v-if="message.isVoice" class="voice-tag">🎤</span>
        <span class="text">{{ message.content }}</span>
        <!-- TTS 语音播放按钮 -->
        <button
          v-if="message.role === 'assistant' && !message.isError && message.content"
          class="tts-play-btn"
          :class="{ playing: playing }"
          @click.stop="playTTS"
          :title="playing ? '重新播放' : '播放语音'"
        >{{ playing ? '🔊' : '🔈' }}</button>
      </div>
      <div class="time">{{ formatTime(message.timestamp) }}</div>
      <!-- 反馈按钮：仅 AI 消息显示 -->
      <div v-if="message.role === 'assistant' && !message.isError" class="feedback-bar">
        <button
          class="fb-btn"
          :class="{ active: feedbackGiven === 'like' }"
          @click="giveFeedback('like')"
          title="有帮助"
        >👍</button>
        <button
          class="fb-btn"
          :class="{ active: feedbackGiven === 'dislike' }"
          @click="giveFeedback('dislike')"
          title="没帮助"
        >👎</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-message {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  max-width: 80%;
}

.chat-message.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.chat-message.assistant {
  margin-right: auto;
}

.chat-message.error .bubble.assistant {
  background: #fef0f0;
  border-color: #fbc4c4;
  color: #f56c6c;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
}

.chat-message.user .content-wrapper {
  align-items: flex-end;
}

.bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

.bubble.user {
  background: #409eff;
  color: #fff;
  border-top-right-radius: 4px;
}

.bubble.assistant {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 4px;
}

.voice-tag {
  margin-right: 4px;
}

.time {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.feedback-bar {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}

.fb-btn {
  background: none;
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  padding: 2px 10px;
  cursor: pointer;
  font-size: 13px;
  line-height: 1.6;
  transition: all 0.15s;
}

.fb-btn:hover {
  background: #f5f7fa;
  border-color: #c0c4cc;
  transform: scale(1.1);
}

.fb-btn.active {
  background: #e8f0eb;
  border-color: #5a8a6a;
  transform: scale(1.15);
}

/* TTS 播放按钮 */
.tts-play-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-left: 8px;
  background: none;
  border: 1px solid #dcdfe6;
  border-radius: 14px;
  cursor: pointer;
  font-size: 13px;
  opacity: 0;
  transition: all 0.2s;
  vertical-align: middle;
  flex-shrink: 0;
}
.chat-message:hover .tts-play-btn {
  opacity: 0.7;
}
.tts-play-btn:hover {
  opacity: 1 !important;
  background: #ecf5ff;
  border-color: #409eff;
}
.tts-play-btn.playing {
  opacity: 1;
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}
</style>
