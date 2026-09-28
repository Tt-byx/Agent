<script setup>
import { ref, onMounted, onUnmounted, watch, provide } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { getConversations, getConversationMessages, deleteConversation, renameConversation, searchConversations } from '@/api/auth'
import { getVoices, setVoice } from '@/api/voice'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import Live2DCanvas from '@/components/live2d/Live2DCanvas.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const chatStore = useChatStore()
const userStore = useUserStore()
const live2dRef = ref(null)
const isLive2DReady = ref(false)

// 音色选择
const voiceOptions = ref([])
const currentVoice = ref('zh-CN-XiaoxiaoNeural')
provide('currentVoice', currentVoice)

async function loadVoices() {
  try {
    const data = await getVoices()
    if (data?.voices) {
      voiceOptions.value = data.voices
      if (data.current) currentVoice.value = data.current
    }
  } catch { /* ignore */ }
}

async function handleVoiceChange(voiceId) {
  currentVoice.value = voiceId
  try {
    await setVoice(voiceId)
    ElMessage.success('音色已切换')
  } catch {
    ElMessage.warning('音色切换失败')
  }
}

const conversations = ref([])
const activeConvId = ref(null)
const editingId = ref(null)
const editTitle = ref('')

// ── 对话搜索 ──
const searchKeyword = ref('')
const searchDateRange = ref(null)
const searchResults = ref(null) // null=未搜索，[] = 搜索无结果
const isSearching = ref(false)
let _searchTimer = null

function doSearch() {
  clearTimeout(_searchTimer)
  _searchTimer = setTimeout(async () => {
    const kw = searchKeyword.value.trim()
    if (!kw && !searchDateRange.value) {
      searchResults.value = null
      return
    }
    isSearching.value = true
    try {
      const params = {}
      if (kw) params.keyword = kw
      if (searchDateRange.value && searchDateRange.value.length === 2) {
        params.startDate = searchDateRange.value[0]
        params.endDate = searchDateRange.value[1]
      }
      const data = await searchConversations(params)
      searchResults.value = Array.isArray(data) ? data : []
    } catch {
      searchResults.value = []
    } finally {
      isSearching.value = false
    }
  }, 300)
}

function clearSearch() {
  searchKeyword.value = ''
  searchDateRange.value = null
  searchResults.value = null
}

function highlightText(text, keyword) {
  if (!text || !keyword) return text || ''
  const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return text.replace(new RegExp(escaped, 'gi'), match => `<mark>${match}</mark>`)
}

const interestTags = [
  { icon: '🏛️', label: '历史文化', prompt: '我对历史文化感兴趣，请推荐一条游览路线', type: '' },
  { icon: '🌿', label: '自然风光', prompt: '我喜欢自然风光，请推荐一条游览路线', type: 'success' },
  { icon: '👨‍👩‍👧', label: '亲子互动', prompt: '我带小朋友来玩，推荐一条亲子路线', type: 'warning' },
  { icon: '📸', label: '摄影打卡', prompt: '我想拍照打卡，推荐一条摄影路线', type: 'danger' },
  { icon: '🍜', label: '美食禅意', prompt: '我想品尝美食和体验禅意文化，推荐一条路线', type: 'info' },
]

const durationOptions = [
  { label: '2小时', value: '2小时' },
  { label: '半天', value: '半天' },
  { label: '全天', value: '全天' },
]
const selectedDuration = ref('')

// ── 发送消息 ──
function handleSendText(message) {
  chatStore.sendTextMessage(message)
}

function sendInterestTag(tag) {
  let prompt = tag.prompt
  if (selectedDuration.value) {
    prompt += `，我可用时间为${selectedDuration.value}`
  }
  handleSendText(prompt)
}

// ── 对话列表管理 ──
async function loadConversations() {
  try {
    const data = await getConversations()
    conversations.value = Array.isArray(data) ? data : []
  } catch { conversations.value = [] }
}

async function loadLastConversation() {
  await loadConversations()
  if (conversations.value.length > 0) await switchConversation(conversations.value[0])
}

async function switchConversation(conv) {
  if (activeConvId.value === conv.id) return
  try {
    activeConvId.value = conv.id
    const msgs = await getConversationMessages(conv.id)
    chatStore.clearMessages()
    if (Array.isArray(msgs) && msgs.length > 0) {
      chatStore.messages.splice(0, 0, ...msgs.map(m => ({
        role: m.role, content: m.content, timestamp: new Date(m.createdAt).getTime(),
      })))
    }
    chatStore.sessionId = conv.sessionId
  } catch { ElMessage.error('加载对话失败') }
}

function switchToSearchResult(item) {
  searchResults.value = null
  searchKeyword.value = ''
  searchDateRange.value = null
  switchConversation({ id: item.id, sessionId: item.sessionId })
}

function newConversation() {
  activeConvId.value = null
  chatStore.clearMessages()
}

async function handleDelete(conv, event) {
  event.stopPropagation()
  try {
    await ElMessageBox.confirm(`删除「${conv.title}」？`, '确认删除', { type: 'warning' })
    await deleteConversation(conv.id)
    if (activeConvId.value === conv.id) { activeConvId.value = null; chatStore.clearMessages() }
    await loadConversations()
  } catch (err) { if (err !== 'cancel') ElMessage.error('删除失败') }
}

function startRename(conv, event) {
  event.stopPropagation()
  editingId.value = conv.id
  editTitle.value = conv.title
}

async function confirmRename(conv) {
  const newTitle = editTitle.value.trim()
  if (!newTitle || newTitle === conv.title) { editingId.value = null; return }
  try { await renameConversation(conv.id, newTitle); conv.title = newTitle } catch { ElMessage.error('重命名失败') }
  editingId.value = null
}

// 对话完成后刷新列表
watch(() => chatStore.loading, async (loading, oldLoading) => {
  if (oldLoading && !loading) {
    await loadConversations()
    if (chatStore.sessionId) {
      const found = conversations.value.find(c => c.sessionId === chatStore.sessionId)
      if (found) activeConvId.value = found.id
    }
  }
})

// ── 数字人口型同步 ──
// 文字口型同步：收到新文本+时长时启动 viseme 驱动
watch([() => chatStore.lipSyncText, () => chatStore.lipSyncDuration], ([text, duration]) => {
  if (text && live2dRef.value && duration > 0) {
    live2dRef.value.startTextLipSync(text, duration)
  }
})

// 口型同步 + 表情重置 由 loading/audioPlaying 联合控制（见下方 watch）
// 音频播放结束时停止口型同步 — 已合并到 loading watcher 中

let _expressionResetTimer = null

// loading 状态变化：控制口型同步 + 表情重置
// 改进：等待音频播放完毕后再重置表情，避免覆盖正在播放的TTS中的表情
watch(() => chatStore.loading, (loading) => {
  if (loading) {
    if (_expressionResetTimer) { clearTimeout(_expressionResetTimer); _expressionResetTimer = null }
    live2dRef.value?.setExpression('Normal')
    chatStore.currentExpression = 'Normal'
  } else {
    live2dRef.value?.stopTextLipSync()
    live2dRef.value?.setLipSync(0)
    // 不再立即设置重置定时器，等音频播完后再由 audioPlaying watcher 处理
    // 如果没有音频（错误情况等），用4秒兜底重置
    if (!chatStore.audioPlaying) {
      _scheduleExpressionReset()
    }
  }
})

function _scheduleExpressionReset() {
  if (_expressionResetTimer) clearTimeout(_expressionResetTimer)
  _expressionResetTimer = setTimeout(() => {
    _expressionResetTimer = null
    live2dRef.value?.setExpression('Normal')
    chatStore.currentExpression = 'Normal'
  }, 4000)
}

// 音频播放结束时：停止口型同步 + 安排表情重置
watch(() => chatStore.audioPlaying, (playing) => {
  if (!playing) {
    live2dRef.value?.stopTextLipSync()
    // 音频播完后，延迟重置表情
    if (!chatStore.loading) {
      _scheduleExpressionReset()
    }
  }
})

// ── 数字人表情驱动 ──
function applyExpression(expr) {
  if (expr && live2dRef.value) {
    live2dRef.value.setExpression(expr)
  }
}

watch(() => chatStore.currentExpression, (expr) => {
  if (expr && live2dRef.value) {
    live2dRef.value.setExpression(expr)
  }
})

function onLive2DReady() {
  isLive2DReady.value = true
  chatStore.setExpressionCallback(applyExpression)
}

function formatTime(time) {
  if (!time) return ''
  const d = new Date(time)
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  chatStore.initWebSocket()
  await userStore.fetchMe()
  await loadLastConversation()
  loadVoices()
})

onUnmounted(() => {
  chatStore.destroy()
})
</script>

<template>
  <div class="chat-view">
    <div class="chat-layout">
      <div class="avatar-panel">
        <Live2DCanvas ref="live2dRef" class="live2d-wrapper" @ready="onLive2DReady" />
      </div>

      <div class="chat-panel">
        <div v-if="!chatStore.isWsConnected" class="connection-hint">
          <span class="dot"></span> 聊天服务未连接
        </div>

        <div class="chat-toolbar">
          <span class="toolbar-label">🎙️ 音色</span>
          <el-select
            :model-value="currentVoice"
            @change="handleVoiceChange"
            size="small"
            style="width: 160px"
            :teleported="false"
          >
            <el-option
              v-for="v in voiceOptions"
              :key="v.id"
              :label="`${v.name}（${v.gender}）`"
              :value="v.id"
            />
          </el-select>
        </div>

        <div class="chat-container">
          <div v-if="chatStore.messages.length === 0" class="interest-tags">
            <p class="tags-title">🎯 我感兴趣的方向</p>
            <div class="tags-row">
              <el-tag v-for="tag in interestTags" :key="tag.label" :type="tag.type"
                class="interest-tag" effect="plain" @click="sendInterestTag(tag)">
                {{ tag.icon }} {{ tag.label }}
              </el-tag>
            </div>
            <div class="duration-row">
              <span class="duration-label">⏱ 可用时间：</span>
              <el-radio-group v-model="selectedDuration" size="small">
                <el-radio-button
                  v-for="d in durationOptions"
                  :key="d.value"
                  :label="d.value"
                >{{ d.label }}</el-radio-button>
              </el-radio-group>
              <el-button v-if="selectedDuration" text size="small" @click="selectedDuration = ''">清除</el-button>
            </div>
          </div>

          <MessageList :messages="chatStore.messages" :loading="chatStore.loading">
            <ChatMessage v-for="(msg, i) in chatStore.messages" :key="i" :message="msg" />
          </MessageList>
          <ChatInput :loading="chatStore.loading" @send-text="handleSendText" />
        </div>
      </div>

      <div class="history-panel">
        <div class="history-header">
          <span class="history-title">💬 历史对话</span>
          <el-button size="small" text type="primary" @click="newConversation">
            <el-icon><Plus /></el-icon> 新对话
          </el-button>
        </div>

        <!-- 搜索区域 -->
        <div class="search-section">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索对话..."
            size="small"
            clearable
            @input="doSearch"
            @clear="clearSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-date-picker
            v-model="searchDateRange"
            type="daterange"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            size="small"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            :teleported="false"
            @change="doSearch"
            @clear="doSearch"
            style="width: 100%; margin-top: 6px;"
          />
        </div>

        <!-- 搜索结果列表 -->
        <div v-if="searchResults !== null" class="history-list">
          <div v-if="searchResults.length === 0" class="history-empty">
            {{ isSearching ? '搜索中...' : '未找到匹配的对话' }}
          </div>
          <div v-for="item in searchResults" :key="item.id"
            class="history-item" :class="{ active: activeConvId === item.id }"
            @click="switchToSearchResult(item)">
            <div class="conv-title" v-html="highlightText(item.title || '新对话', searchKeyword)"></div>
            <div v-if="item.matchedSnippet" class="conv-snippet" v-html="highlightText(item.matchedSnippet, searchKeyword)"></div>
            <div class="conv-meta">
              <span class="conv-time">{{ formatTime(item.updatedAt) }}</span>
            </div>
          </div>
        </div>

        <!-- 正常对话列表 -->
        <div v-else class="history-list">
          <div v-for="conv in conversations" :key="conv.id"
            class="history-item" :class="{ active: activeConvId === conv.id }"
            @click="switchConversation(conv)">
            <template v-if="editingId === conv.id">
              <input v-model="editTitle" class="rename-input" maxlength="30"
                @keyup.enter="confirmRename(conv)" @keyup.escape="editingId = null"
                @blur="confirmRename(conv)" @click.stop autofocus />
            </template>
            <template v-else>
              <div class="conv-title">{{ conv.title || '新对话' }}</div>
              <div class="conv-meta">
                <span class="conv-time">{{ formatTime(conv.updatedAt) }}</span>
                <span class="conv-actions">
                  <el-icon class="action-icon" @click.stop="startRename(conv, $event)"><Edit /></el-icon>
                  <el-icon class="action-icon danger" @click.stop="handleDelete(conv, $event)"><Delete /></el-icon>
                </span>
              </div>
            </template>
          </div>
          <div v-if="conversations.length === 0" class="history-empty">暂无历史对话</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-view { width: 100%; height: calc(100vh - 60px); padding: 12px; display: flex; flex-direction: column; }
.chat-layout { flex: 1; display: flex; gap: 12px; min-height: 0; }
.avatar-panel { width: 360px; min-width: 280px; display: flex; flex-direction: column; background: linear-gradient(180deg, #e8f4f8 0%, #d4e9d7 40%, #f0ebe3 100%); border-radius: 12px; overflow: hidden; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }
.live2d-wrapper { flex: 1; min-height: 0; }
.chat-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.connection-hint { display: flex; align-items: center; gap: 8px; padding: 6px 14px; background: #fdf6ec; border-radius: 6px; color: #e6a23c; font-size: 12px; flex-shrink: 0; }
.connection-hint .dot { width: 6px; height: 6px; border-radius: 50%; background: #e6a23c; animation: blink 1.5s ease-in-out infinite; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.chat-toolbar { display: flex; align-items: center; gap: 8px; padding: 6px 12px; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); flex-shrink: 0; }
.toolbar-label { font-size: 13px; color: #606266; white-space: nowrap; }
.chat-container { flex: 1; display: flex; flex-direction: column; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); overflow: hidden; min-height: 0; }
.interest-tags { padding: 20px; border-bottom: 1px solid #f0f0f0; flex-shrink: 0; }
.tags-title { font-size: 14px; color: #606266; margin-bottom: 12px; }
.tags-row { display: flex; gap: 10px; flex-wrap: wrap; }
.duration-row { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding-top: 10px; border-top: 1px solid #f0f0f0; }
.duration-label { font-size: 13px; color: #909399; flex-shrink: 0; }
.interest-tag { cursor: pointer; font-size: 13px; padding: 8px 16px; border-radius: 20px; transition: all 0.2s; }
.interest-tag:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.history-panel { width: 240px; min-width: 220px; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.06); display: flex; flex-direction: column; overflow: hidden; }
.history-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 14px 10px; border-bottom: 1px solid #f0f0f0; }
.history-title { font-size: 14px; font-weight: 600; color: #333; }
.history-list { flex: 1; overflow-y: auto; padding: 6px; }
.history-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: background 0.15s; margin-bottom: 2px; }
.history-item:hover { background: #f5f7fa; }
.history-item:hover .conv-actions { opacity: 1; }
.history-item.active { background: #e8f5e9; border-left: 3px solid #5a8a6a; }
.conv-title { font-size: 13px; color: #333; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conv-meta { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.conv-time { font-size: 11px; color: #999; }
.conv-actions { display: flex; gap: 6px; opacity: 0; transition: opacity 0.15s; }
.action-icon { font-size: 13px; color: #999; cursor: pointer; padding: 2px; border-radius: 4px; transition: all 0.15s; }
.action-icon:hover { color: #5a8a6a; background: rgba(90,138,106,0.1); }
.action-icon.danger:hover { color: #e05050; background: rgba(224,80,80,0.1); }
.rename-input { width: 100%; border: 1px solid #5a8a6a; border-radius: 4px; padding: 4px 8px; font-size: 13px; outline: none; font-family: inherit; }
.history-empty { text-align: center; color: #ccc; font-size: 13px; padding: 32px 0; }
.search-section { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; flex-shrink: 0; }
.search-section :deep(.el-date-editor) { --el-date-editor-width: 100%; }
.conv-snippet { font-size: 11px; color: #999; margin-top: 3px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.conv-snippet :deep(mark), .conv-title :deep(mark) { background: #fef08a; color: #333; padding: 0 1px; border-radius: 2px; }
@media (max-width: 1100px) { .history-panel { display: none; } }
@media (max-width: 900px) { .chat-layout { flex-direction: column; } .avatar-panel { width: 100%; height: 300px; min-width: unset; } }
</style>
