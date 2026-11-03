<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { getConversations, getConversationMessages, deleteConversation, renameConversation, searchConversations } from '@/api/auth'
import { ensureVisitorToken } from '@/api/chat'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const chatStore = useChatStore()
const userStore = useUserStore()
const router = useRouter()
const route = useRoute()

const conversations = ref([])
const activeConvId = ref(null)
const editingId = ref(null)
const editTitle = ref('')
/** 侧栏折叠：窄屏时自动收起，桌面端由用户手动切换 */
const collapsed = ref(false)

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
  { icon: '🏛️', label: '历史文化', prompt: '我对历史文化感兴趣，请推荐一条游览路线' },
  { icon: '🌿', label: '自然风光', prompt: '我喜欢自然风光，请推荐一条游览路线' },
  { icon: '👨‍👩‍👧', label: '亲子互动', prompt: '我带小朋友来玩，推荐一条亲子路线' },
  { icon: '📸', label: '摄影打卡', prompt: '我想拍照打卡，推荐一条摄影路线' },
  { icon: '🍜', label: '美食禅意', prompt: '我想品尝美食和体验禅意文化，推荐一条路线' },
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
  // 2026-11-03 修复：未登录直接返回，不发请求。
  // 原来不管身份就请求 /api/conversations —— 游客拿到的 401 会被 request.js 的
  // 全局拦截器接住，弹出「登录已过期」并强制跳登录页。
  // 这是「点开始对话被弹到登录页」的第二个原因（第一个是路由守卫的 requiresAuth）。
  if (!userStore.isLoggedIn()) {
    conversations.value = []
    return
  }
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

/**
 * 会话按时间分组（今天 / 昨天 / 更早）—— 对照 DeepSeek 侧栏的分组方式。
 * 用「距今天数」而不是日期字符串比较，避免跨时区/跨零点时的边界错误。
 */
const groupedConversations = computed(() => {
  const today = []
  const yesterday = []
  const earlier = []
  const now = Date.now()
  for (const c of conversations.value) {
    const t = new Date(c.updatedAt || c.createdAt || 0).getTime()
    const days = (now - t) / 86400000
    if (days < 1) today.push(c)
    else if (days < 2) yesterday.push(c)
    else earlier.push(c)
  }
  return [
    { label: '今天', items: today },
    { label: '昨天', items: yesterday },
    { label: '更早', items: earlier },
  ].filter((g) => g.items.length)
})

function formatTime(time) {
  if (!time) return ''
  const d = new Date(time)
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  // 2026-11-03：游客进对话页时先领一张游客凭证（决定长期记忆记在谁名下）。
  // 故意不 await —— 它和下面的初始化并行，不能拖慢首屏；
  // 真正发消息时 streamChatMessage 内部还会再 ensure 一次（同一个请求会被去重）。
  ensureVisitorToken()
  await userStore.fetchMe()
  await loadLastConversation()

  // 首页那颗输入框会带着问题跳过来（?q=...），这里直接发出去，
  // 用户不必再敲一遍。发完立刻清掉 query —— 否则刷新页面会重复发送。
  const pending = route.query.q
  if (typeof pending === 'string' && pending.trim()) {
    await nextTick()
    chatStore.sendTextMessage(pending.trim())
    router.replace({ path: '/chat' })
  }
})

onUnmounted(() => {
  chatStore.destroy()
})
</script>

<template>
  <div class="chat-view" :class="{ 'sidebar-collapsed': collapsed }">
    <!-- ═════════ 左侧栏 ═════════
         对照 DeepSeek：浅灰底、顶部折叠按钮、胶囊式「开启新对话」、
         会话按「今天/昨天/更早」分组、底部用户信息。 -->
    <aside class="chat-sidebar">
      <div class="sidebar-head">
        <button class="icon-btn" type="button"
                :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
                @click="collapsed = !collapsed">
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" aria-hidden="true">
            <rect x="3" y="4.5" width="18" height="15" rx="2.5" stroke="currentColor" stroke-width="1.5"/>
            <path d="M9 4.5v15" stroke="currentColor" stroke-width="1.5"/>
          </svg>
        </button>
      </div>

      <button class="new-chat" type="button" @click="newConversation">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
        </svg>
        <span>开启新对话</span>
      </button>

      <div v-if="userStore.isLoggedIn()" class="sidebar-search">
        <el-input v-model="searchKeyword" placeholder="搜索对话" size="small" clearable
                  @input="doSearch" @clear="clearSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <div class="sidebar-list">
        <div v-if="!userStore.isLoggedIn()" class="guest-block">
          <p>登录后可以保存对话，随时接着聊。</p>
          <button class="link-btn" type="button" @click="router.push('/login')">去登录</button>
        </div>

        <template v-else-if="searchResults !== null">
          <p class="group-label">{{ isSearching ? '搜索中…' : '搜索结果' }}</p>
          <p v-if="searchResults.length === 0" class="list-empty">没有匹配的对话</p>
          <button v-for="item in searchResults" :key="item.id" type="button"
                  class="conv-item" :class="{ active: activeConvId === item.id }"
                  @click="switchToSearchResult(item)">
            <span class="conv-title" v-html="highlightText(item.title || '新对话', searchKeyword)"></span>
            <span v-if="item.matchedSnippet" class="conv-snippet"
                  v-html="highlightText(item.matchedSnippet, searchKeyword)"></span>
          </button>
        </template>

        <template v-else>
          <template v-for="group in groupedConversations" :key="group.label">
            <p class="group-label">{{ group.label }}</p>
            <div v-for="conv in group.items" :key="conv.id" class="conv-row">
              <input v-if="editingId === conv.id" v-model="editTitle" class="rename-input"
                     maxlength="30" autofocus
                     @keyup.enter="confirmRename(conv)" @keyup.escape="editingId = null"
                     @blur="confirmRename(conv)" />
              <button v-else type="button" class="conv-item"
                      :class="{ active: activeConvId === conv.id }"
                      @click="switchConversation(conv)">
                <span class="conv-title">{{ conv.title || '新对话' }}</span>
              </button>
              <span v-if="editingId !== conv.id" class="conv-actions">
                <button class="icon-mini" type="button" title="重命名"
                        @click.stop="startRename(conv, $event)">
                  <el-icon><Edit /></el-icon>
                </button>
                <button class="icon-mini danger" type="button" title="删除"
                        @click.stop="handleDelete(conv, $event)">
                  <el-icon><Delete /></el-icon>
                </button>
              </span>
            </div>
          </template>
          <p v-if="conversations.length === 0" class="list-empty">还没有对话</p>
        </template>
      </div>

      <div class="sidebar-foot">
        <template v-if="userStore.user">
          <span class="avatar">{{ (userStore.user.nickname || userStore.user.username || '?').slice(0, 1).toUpperCase() }}</span>
          <span class="foot-name">{{ userStore.user.nickname || userStore.user.username }}</span>
        </template>
        <span v-else class="foot-name muted">游客模式 · 未登录</span>
      </div>
    </aside>

    <!-- ═════════ 主区 ═════════ -->
    <main class="chat-main" :class="{ 'is-empty': chatStore.messages.length === 0 }">
      <div class="chat-scroll" :class="{ 'is-empty': chatStore.messages.length === 0 }">
        <!-- 空状态：logo + 大标题 + 兴趣标签，整体垂直居中 -->
        <div v-if="chatStore.messages.length === 0" class="empty-state">
          <div class="empty-mark" aria-hidden="true">
            <svg viewBox="0 0 32 32" width="38" height="38" fill="none">
              <path d="M16 4 6 26h20L16 4Z" stroke="currentColor" stroke-width="1.3"
                    stroke-linejoin="round" opacity="0.45"/>
              <path d="M16 9.5 9.5 24.5h13L16 9.5Z" stroke="currentColor"
                    stroke-width="1.4" stroke-linejoin="round"/>
            </svg>
          </div>

          <h1 class="empty-title">嗨！今天想聊些什么？</h1>

          <div class="interest-tags">
            <button v-for="tag in interestTags" :key="tag.label" type="button"
                    class="interest-tag" @click="sendInterestTag(tag)">
              {{ tag.icon }} {{ tag.label }}
            </button>
          </div>

          <div class="duration-row">
            <span class="duration-label">可用时间</span>
            <el-radio-group v-model="selectedDuration" size="small">
              <el-radio-button v-for="d in durationOptions" :key="d.value" :value="d.value">
                {{ d.label }}
              </el-radio-button>
            </el-radio-group>
            <button v-if="selectedDuration" class="link-btn" type="button"
                    @click="selectedDuration = ''">清除</button>
          </div>
        </div>

        <!-- 消息区 -->
        <div v-else class="message-wrap">
          <MessageList :messages="chatStore.messages" :loading="chatStore.loading">
            <div v-if="chatStore.agentSteps.length" class="agent-steps">
              <span v-for="s in chatStore.agentSteps" :key="s.step" class="agent-step"
                    :class="{ done: !s.text.endsWith('思考中…') }">{{ s.text }}</span>
            </div>
            <ChatMessage v-for="(msg, i) in chatStore.messages" :key="i" :message="msg" />
          </MessageList>
        </div>
      </div>

      <!-- 输入区：始终吸底、居中、限宽 -->
      <div class="composer-dock">
        <ChatInput :loading="chatStore.loading" :streaming="chatStore.loading"
                   @send-text="handleSendText" @stop="chatStore.stopGenerating()" />
        <p v-if="chatStore.lastTrace?.traceId" class="trace-line">
          traceId · {{ chatStore.lastTrace.traceId }}
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════
   对话页 —— 对照 chat.deepseek.com 重做
   ───────────────────────────────────────────────────────────
   改动前的结构缺陷：三张白卡片（兴趣区 / 对话区 / 输入区）并排，
   侧栏在右边。视觉上像「一个后台页面里嵌了个聊天框」。
   现在按 DeepSeek 的方式：左侧栏 + 纯白主区，没有卡片边界，
   输入框吸底居中 —— 整个页面就是对话本身。
   ═══════════════════════════════════════════════════════════ */

.chat-view {
  display: flex;
  width: 100%;
  height: calc(100vh - 64px);
  background: var(--color-surface);
  overflow: hidden;
}

/* ───────── 左侧栏 ───────── */
.chat-sidebar {
  width: 248px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #FAFAF9;
  border-right: 1px solid var(--color-line);
  transition: width var(--dur-base) var(--ease-out),
              border-color var(--dur-base) ease-out;
  overflow: hidden;
}

.sidebar-collapsed .chat-sidebar {
  width: 0;
  border-right-color: transparent;
}

.sidebar-head {
  display: flex;
  justify-content: flex-end;
  padding: 12px 12px 4px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              color var(--dur-fast) ease-out;
}

.icon-btn:hover {
  background: var(--color-bg-tint);
  color: var(--color-ink);
}

/* 「开启新对话」：白底 + 发丝边框的胶囊，和 DeepSeek 一致 */
.new-chat {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 12px 10px;
  height: 40px;
  padding: 0 14px;
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-full);
  background: var(--color-surface);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-ink);
  cursor: pointer;
  transition: border-color var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out;
}

.new-chat:hover {
  border-color: var(--color-brand);
  box-shadow: var(--shadow-1);
}

.sidebar-search {
  padding: 0 12px 8px;
}

.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

/* 分组标题：比正文更小、更淡，只做分隔提示 */
.group-label {
  padding: 12px 8px 4px;
  font-size: var(--text-xs);
  color: var(--color-muted);
  letter-spacing: 0.04em;
}

.conv-row {
  position: relative;
}

/* 会话项：纯文字 + 圆角 hover，不做卡片 —— 这是 DeepSeek 侧栏的关键质感 */
.conv-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--color-ink-soft);
  text-align: left;
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              color var(--dur-fast) ease-out;
}

.conv-item:hover {
  background: var(--color-bg-tint);
}

.conv-item.active {
  background: var(--color-brand-soft);
  color: var(--color-brand);
}

.conv-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-snippet {
  display: block;
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-snippet :deep(mark),
.conv-title :deep(mark) {
  background: #FDF3C7;
  color: var(--color-ink);
  padding: 0 1px;
  border-radius: 2px;
}

/* 行内操作：默认隐藏，hover 才出现（和 DeepSeek 一致，避免列表太吵） */
.conv-actions {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  gap: 2px;
  padding-left: 12px;
  background: linear-gradient(90deg, transparent, var(--color-bg-tint) 40%);
  opacity: 0;
  transition: opacity var(--dur-fast) ease-out;
}

.conv-row:hover .conv-actions,
.conv-row:focus-within .conv-actions {
  opacity: 1;
}

.icon-mini {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  font-size: 12px;
  transition: color var(--dur-fast) ease-out,
              background var(--dur-fast) ease-out;
}

.icon-mini:hover {
  color: var(--color-brand);
  background: var(--color-surface);
}

.icon-mini.danger:hover {
  color: var(--color-danger);
}

.rename-input {
  width: 100%;
  padding: 7px 9px;
  border: 1px solid var(--color-brand);
  border-radius: var(--radius-sm);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-ink);
  outline: none;
}

.guest-block {
  padding: 20px 10px;
  text-align: center;
  font-size: var(--text-xs);
  line-height: 1.8;
  color: var(--color-muted);
}

.guest-block p { margin-bottom: 10px; }

.link-btn {
  border: none;
  background: none;
  padding: 4px 2px;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-brand);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.list-empty {
  padding: 16px 10px;
  font-size: var(--text-xs);
  color: var(--color-muted);
  text-align: center;
}

/* 底部用户位：和 DeepSeek 一样，头像 + 名字，淡而不抢 */
.sidebar-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--color-line);
}

.avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-full);
  background: var(--color-brand-soft);
  color: var(--color-brand);
  font-size: var(--text-xs);
  font-weight: 600;
  flex-shrink: 0;
}

.foot-name {
  font-size: var(--text-xs);
  color: var(--color-ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foot-name.muted { color: var(--color-muted); }

/* ───────── 主区 ───────── */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
}

/* 空状态时整列垂直居中：scorll 区收缩到内容高度，
   输入框自然跟在标题下方 40px 处 —— 而不是像对话中那样吸在底部。
   DeepSeek 的区别就在这里：没开始对话时，输入框是内容的一部分；
   一旦开聊，它才变成贴底的输入栏。 */
.chat-main.is-empty {
  justify-content: center;
}

.chat-main.is-empty .chat-scroll {
  flex: 0 0 auto;
  overflow: visible;
}

.chat-main.is-empty .composer-dock {
  margin-top: var(--space-6);
}

.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* 空状态时让内容在垂直方向居中，和 DeepSeek 一样 */
.chat-scroll.is-empty {
  align-items: center;
  padding: 0 var(--space-5);
}

.empty-state {
  width: 100%;
  max-width: 720px;
  text-align: center;
}

.empty-mark {
  color: var(--color-brand);
  margin-bottom: var(--space-4);
  opacity: 0.9;
}

/* 空状态大标题：宋体 + 正字距，与首页保持同一套语言 */
.empty-title {
  margin: 0 0 var(--space-6);
  font-family: var(--font-display);
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--color-ink);
}

.interest-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
}

/* 五个兴趣标签统一单色系（原来各用一种语义色，一屏四种强调色） */
.interest-tag {
  padding: 8px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  background: var(--color-brand-soft);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-brand);
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              transform var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out;
}

.interest-tag:hover {
  background: #DCE9E2;
  transform: translateY(-2px);
  box-shadow: var(--shadow-1);
}

.interest-tag:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

.duration-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-5);
  flex-wrap: wrap;
}

.duration-label {
  font-size: var(--text-xs);
  color: var(--color-muted);
}

/* 消息区 */
.message-wrap {
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: var(--space-5) var(--space-5) 0;
}

.agent-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--space-3);
}

.agent-step {
  padding: 3px 10px;
  border-radius: var(--radius-full);
  background: var(--color-bg-tint);
  font-size: var(--text-xs);
  color: var(--color-muted);
  transition: color var(--dur-base) ease-out,
              background var(--dur-base) ease-out;
}

.agent-step.done {
  background: var(--color-brand-soft);
  color: var(--color-brand);
}

/* 输入区：吸底、居中、限宽 —— 这是 DeepSeek 最被记住的一处排版 */
.composer-dock {
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: var(--space-4) var(--space-5) var(--space-5);
  flex-shrink: 0;
}

.trace-line {
  margin-top: 6px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-muted);
  text-align: center;
  opacity: 0.7;
}

/* ───────── 响应式 ───────── */
@media (max-width: 900px) {
  .chat-sidebar { width: 200px; }
  .message-wrap, .composer-dock { max-width: 100%; }
}

@media (max-width: 720px) {
  /* 窄屏默认收起侧栏（用户仍可点按钮展开） */
  .chat-sidebar { width: 0; border-right-color: transparent; }
  .sidebar-collapsed .chat-sidebar { width: 200px; }
  .empty-title { font-size: 22px; }
}
</style>
