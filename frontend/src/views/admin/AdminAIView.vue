<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { adminAIChat, adminAIRecommend, getAdminAIVisitors } from '@/api/adminAI'
import { ElMessage } from 'element-plus'

const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const visitors = ref([])
const selectedVisitor = ref(null)
const recommendLoading = ref(false)

const quickQuestions = [
  '哪些景区消费最高？',
  '游客满意度如何分布？',
  '哪个年龄段消费能力最强？',
  '淡旺季客流有什么规律？',
  '如何提升游客二次消费？',
]

onMounted(async () => {
  messages.value.push({
    role: 'assistant',
    content: '你好！我是景区运营 AI 助手。你可以问我关于游客行为数据的任何问题，也可以选择一个游客查看营销推荐。',
  })
  try {
    const data = await getAdminAIVisitors()
    visitors.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.error('获取游客列表失败:', e)
  }
})

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  sending.value = true

  await nextTick()
  scrollToBottom()

  try {
    const res = await adminAIChat(text)
    messages.value.push({
      role: 'assistant',
      content: res?.reply || '暂无回答',
    })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: '请求失败: ' + (e?.message || '未知错误'),
    })
  } finally {
    sending.value = false
    await nextTick()
    scrollToBottom()
  }
}

function handleQuickQuestion(q) {
  inputText.value = q
  sendMessage()
}

async function handleRecommend() {
  if (!selectedVisitor.value) {
    ElMessage.warning('请先选择一个游客')
    return
  }
  const visitor = visitors.value.find(v => v.userId === selectedVisitor.value)
  recommendLoading.value = true
  try {
    const res = await adminAIRecommend(selectedVisitor.value)
    messages.value.push({
      role: 'assistant',
      content: `【营销推荐 - ${visitor?.nickname || '游客'}】\n${res?.suggestions || '暂无推荐'}`,
    })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: '推荐生成失败: ' + (e?.message || '未知错误'),
    })
  } finally {
    recommendLoading.value = false
    await nextTick()
    scrollToBottom()
  }
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function scrollToBottom() {
  const el = document.querySelector('.chat-messages')
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<template>
  <div class="ai-page">
    <!-- 左侧聊天区 -->
    <div class="chat-section">
      <div class="chat-messages">
        <div
          v-for="(msg, i) in messages"
          :key="i"
          class="msg-row"
          :class="msg.role"
        >
          <div class="msg-avatar">
            <el-icon v-if="msg.role === 'user'" :size="18"><User /></el-icon>
            <el-icon v-else :size="18"><Monitor /></el-icon>
          </div>
          <div class="msg-bubble">
            <div class="msg-content">{{ msg.content }}</div>
          </div>
        </div>
        <div v-if="sending" class="msg-row assistant">
          <div class="msg-avatar"><el-icon :size="18"><Monitor /></el-icon></div>
          <div class="msg-bubble">
            <div class="msg-content typing">思考中...</div>
          </div>
        </div>
      </div>

      <div class="chat-input-area">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="2"
          placeholder="输入运营问题，如：哪些景区消费最高？"
          @keydown="handleKeydown"
          :disabled="sending"
          resize="none"
        />
        <el-button
          type="primary"
          :loading="sending"
          @click="sendMessage"
          class="send-btn"
        >
          <el-icon v-if="!sending"><Promotion /></el-icon>
          发送
        </el-button>
      </div>
    </div>

    <!-- 右侧面板 -->
    <div class="side-panel">
      <!-- 快捷问题 -->
      <div class="panel-card">
        <div class="panel-title">
          <el-icon :size="14"><ChatLineSquare /></el-icon>
          快捷问题
        </div>
        <div class="quick-list">
          <div
            v-for="q in quickQuestions"
            :key="q"
            class="quick-item"
            @click="handleQuickQuestion(q)"
          >
            {{ q }}
          </div>
        </div>
      </div>

      <!-- 画像推荐 -->
      <div class="panel-card">
        <div class="panel-title">
          <el-icon :size="14"><UserFilled /></el-icon>
          画像营销推荐
        </div>
        <el-select
          v-model="selectedVisitor"
          placeholder="选择游客"
          style="width: 100%; margin-bottom: 12px"
          filterable
        >
          <el-option
            v-for="v in visitors"
            :key="v.userId"
            :label="v.nickname"
            :value="v.userId"
          >
            <span>{{ v.nickname }}</span>
            <span v-if="v.topTags?.length" style="float:right;color:#8d95a3;font-size:11px">
              {{ v.topTags.slice(0, 2).join('、') }}
            </span>
          </el-option>
        </el-select>
        <el-button
          type="primary"
          :loading="recommendLoading"
          @click="handleRecommend"
          :disabled="!selectedVisitor"
          class="recommend-btn"
          style="width: 100%"
        >
          <el-icon v-if="!recommendLoading"><MagicStick /></el-icon>
          生成营销推荐
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-page {
  display: flex;
  gap: 16px;
  height: calc(100vh - 120px);
  max-width: 1200px;
  margin: 0 auto;
}

/* ── Chat section ── */
.chat-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-card, #fff);
  border-radius: 10px;
  border: 1px solid var(--border-light, #eae8e4);
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.msg-row {
  display: flex;
  gap: 10px;
  max-width: 80%;
}

.msg-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.msg-row.assistant .msg-avatar {
  background: #e8f0eb;
  color: #5a8a6a;
}

.msg-row.user .msg-avatar {
  background: #e8e4f0;
  color: #7b6baa;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.6;
  font-size: 14px;
}

.msg-row.assistant .msg-bubble {
  background: #f5f5f3;
  color: #2d3440;
}

.msg-row.user .msg-bubble {
  background: #5a8a6a;
  color: #fff;
}

.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-content.typing {
  color: #8d95a3;
}

.chat-input-area {
  padding: 16px;
  border-top: 1px solid var(--border-light, #eae8e4);
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.chat-input-area :deep(.el-textarea__inner) {
  border-radius: 8px;
}

.send-btn {
  background: #5a8a6a;
  border-color: #5a8a6a;
  border-radius: 8px;
  height: 42px;
  padding: 0 20px;
  flex-shrink: 0;
}

/* ── Side panel ── */
.side-panel {
  width: 280px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex-shrink: 0;
}

.panel-card {
  background: var(--bg-card, #fff);
  border-radius: 10px;
  border: 1px solid var(--border-light, #eae8e4);
  padding: 16px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #2d3440);
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-light, #eae8e4);
}

.quick-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.quick-item {
  font-size: 13px;
  color: #5a8a6a;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
  background: #f8faf8;
}

.quick-item:hover {
  background: #e8f0eb;
}

.recommend-btn {
  background: #c4956a;
  border-color: #c4956a;
}

.recommend-btn:hover {
  background: #b5845c;
  border-color: #b5845c;
}

@media (max-width: 900px) {
  .ai-page {
    flex-direction: column;
    height: auto;
  }
  .side-panel {
    width: 100%;
    flex-direction: row;
  }
  .panel-card {
    flex: 1;
  }
}
</style>
