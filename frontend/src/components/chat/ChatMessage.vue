<script setup>
import { ref } from 'vue'
import { submitFeedback } from '@/api/analytics'
import { ElMessage } from 'element-plus'

const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
})

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
        <span class="text">{{ message.content }}</span>
        <span v-if="message.streaming" class="cursor">▍</span>
      </div>
      <div class="meta-row">
        <span class="time">{{ formatTime(message.timestamp) }}</span>
        <span v-if="message.ttft" class="metric" title="首字延迟 / 总耗时">
          首字 {{ message.ttft }}ms · 总 {{ message.elapsed }}ms
        </span>
      </div>
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

.time {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.metric {
  font-size: 11px;
  color: #c0c4cc;
}

/* 流式输出的光标：闪烁提示"正在生成" */
.cursor {
  display: inline-block;
  margin-left: 1px;
  color: #409eff;
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
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
</style>
