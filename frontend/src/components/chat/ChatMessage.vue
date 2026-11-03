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
    <!-- 2026-11-03：只给用户留头像。AI 的回答改为无头像、无气泡的纯文本，
         这是 chat.deepseek.com 的做法 —— 回答本身是主体，不该被气泡框住。 -->
    <div v-if="message.role === 'user'" class="avatar">
      <el-avatar :size="30" class="user-avatar">
        <el-icon><User /></el-icon>
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
  margin-bottom: 22px;
  max-width: 82%;
}

/* AI 回答占满内容宽度，读起来更顺（气泡框会把长文本挤窄） */
.chat-message.assistant {
  max-width: 100%;
}

.chat-message.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.chat-message.assistant {
  margin-right: auto;
}

.user-avatar {
  background: var(--color-bg-tint);
  color: var(--color-ink-soft);
}

.chat-message.error .bubble.assistant {
  background: var(--color-danger-soft);
  border-color: var(--color-line-strong);
  color: var(--color-danger);
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
  background: var(--color-bg-tint);
  color: var(--color-ink);          /* 浅灰底必须配深字，白字会看不见 */
  border-top-right-radius: 4px;
}

/* AI：完全没有气泡 —— 去掉边框、底色、内边距和圆角，
   让回答直接落在页面上，长文本读起来不会被框挤窄 */
.bubble.assistant {
  background: transparent;
  color: var(--color-ink);
  border: none;
  padding: 0;
  border-radius: 0;
  font-size: var(--text-base);
  line-height: 1.8;
}

.time {
  font-size: 12px;
  color: var(--color-muted);
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
  color: var(--color-line-strong);
}

/* 流式输出的光标：闪烁提示"正在生成" */
.cursor {
  display: inline-block;
  margin-left: 1px;
  color: var(--color-brand);
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
  border: 1px solid var(--color-line);
  border-radius: 14px;
  padding: 2px 10px;
  cursor: pointer;
  font-size: 13px;
  line-height: 1.6;
  transition: all 0.15s;
}

.fb-btn:hover {
  background: var(--color-bg-tint);
  border-color: var(--color-line-strong);
  transform: scale(1.1);
}

.fb-btn.active {
  background: var(--color-brand-soft);
  border-color: var(--color-brand);
  transform: scale(1.15);
}
</style>
