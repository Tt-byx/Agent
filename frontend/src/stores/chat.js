import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChatMessage } from '@/api/chat'
import { newRequestId } from '@/utils/identity'

/**
 * 对话状态（Wave 5 改造：WebSocket → SSE 真流式）。
 *
 * 为什么换掉 WebSocket：
 *   原实现虽然叫「流式」，但后端是把整段答案一次性推过来（见 ChatWebSocketHandler：
 *   它等 AgentLoop.run() 跑完才 sendToClient），前端收到的第一个 chunk 就是全部内容，
 *   视觉上是「卡一下 → 整段出现」，并没有真正的逐字效果，也拿不到 Agent 的执行步骤。
 *
 *   换成 SSE（POST /api/chat/stream）后，后端逐 token 推送，前端可以：
 *     ① 首字立刻出现（TTFT 前移）；
 *     ② 展示 Agent 的 ReAct 步骤（"正在检索…" / "正在调用工具…"）；
 *     ③ 支持「停止生成」（AbortController）。
 *
 * 保留的能力：会话列表、历史消息加载、错误兜底。
 */
export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const sessionId = ref(null)
  const loading = ref(false)
  // 2026-10-29（D30 收尾）：原来的 isWsConnected 字段已删除 ——
  // WebSocket 链路整体移除后它恒为 true，只是「兼容旧模板」的僵尸字段。
  // 需要判断「能否发送」请直接看 loading。

  /** 当前正在进行的请求，用于「停止生成」 */
  let currentAbort = null
  /** 当前助手消息在 messages 中的下标 */
  let currentAssistantIndex = -1
  /** 本次发送的幂等键（重试时复用；新发送则重建） */
  let currentRequestId = null

  /** Agent 执行步骤（用于气泡上方的"正在检索/调用工具"提示） */
  const agentSteps = ref([])

  /** 运行时指标（traceId 等，可用于调试展示） */
  const lastTrace = ref(null)

  function _pushAssistantSkeleton() {
    currentAssistantIndex = messages.value.length
    messages.value.push({
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      streaming: true,
    })
  }

  function _appendToAssistant(text) {
    if (currentAssistantIndex === -1) _pushAssistantSkeleton()
    messages.value[currentAssistantIndex].content += text
  }

  /**
   * 发送一条消息（SSE 流式）。
   * @param {string} content
   * @param {object} [opts]
   * @param {boolean} [opts.retry] - 是否为「重试上一条」：复用同一 requestId 以触发后端幂等
   */
  function sendTextMessage(content, opts = {}) {
    if (!content || !content.trim() || loading.value) return

    // 非重试 → 新意图，生成新幂等键；重试 → 复用旧键（后端会直接返回首次结果）
    currentRequestId = opts.retry && currentRequestId ? currentRequestId : newRequestId()

    messages.value.push({
      role: 'user',
      content,
      timestamp: Date.now(),
    })

    loading.value = true
    agentSteps.value = []
    currentAssistantIndex = -1
    _pushAssistantSkeleton()

    const startedAt = Date.now()
    let firstTokenAt = 0

    currentAbort = new AbortController()

    streamChatMessage(
      content,
      sessionId.value,
      currentRequestId,
      {
        onMeta: (meta) => {
          lastTrace.value = { traceId: meta.traceId }
        },
        onSession: (payload) => {
          // 服务端下发会话标识：存起来供后续消息复用，
          // 否则每条消息都会被后端当成新会话（历史列表里会出现一堆单条会话）。
          if (payload?.sessionId) sessionId.value = payload.sessionId
        },
        onSessionReset: () => {
          // 旧 sessionId 已被后端判定为不属于当前账号（如切换过账号后残留）：
          // 立刻清掉，避免后续消息继续踩同一个坑。重发由 api 层自动完成。
          sessionId.value = null
        },
        onStep: (step) => {
          if (step.phase === 'start') {
            agentSteps.value.push({ step: step.step, text: `第 ${step.step} 步思考中…` })
          } else if (step.phase === 'end') {
            const s = agentSteps.value.find((x) => x.step === step.step)
            if (s) s.text = step.usedTools ? `第 ${step.step} 步：已调用工具` : `第 ${step.step} 步：组织回答`
          }
        },
        onDiscard: () => {
          // 该步其实是工具调用步，撤销已显示的文本（真流式下会先显示一部分再撤回）
          if (currentAssistantIndex !== -1) {
            messages.value[currentAssistantIndex].content = ''
          }
        },
        onToken: (token) => {
          if (!firstTokenAt) firstTokenAt = Date.now()
          _appendToAssistant(token)
        },
        onDone: (answer, full) => {
          if (currentAssistantIndex !== -1) {
            // 用 done 的完整答案兜底校正（防止丢包导致拼接不一致）
            if (answer && messages.value[currentAssistantIndex].content !== answer) {
              messages.value[currentAssistantIndex].content = answer || full
            }
            messages.value[currentAssistantIndex].streaming = false
            messages.value[currentAssistantIndex].elapsed = Date.now() - startedAt
            messages.value[currentAssistantIndex].ttft = firstTokenAt ? firstTokenAt - startedAt : null
          }
          loading.value = false
          currentAbort = null
        },
        onError: (msg) => {
          if (currentAssistantIndex !== -1) {
            const cur = messages.value[currentAssistantIndex]
            if (!cur.content) {
              cur.content = `抱歉，${msg || '暂时无法回答，请稍后再试。'}`
              cur.isError = true
            } else {
              cur.content += `\n\n[错误: ${msg}]`
              cur.isError = true
            }
            cur.streaming = false
          }
          loading.value = false
          currentAbort = null
        },
      },
      currentAbort.signal
    )
  }

  /** 停止生成 */
  function stopGenerating() {
    if (currentAbort) {
      currentAbort.abort()
      currentAbort = null
    }
    if (currentAssistantIndex !== -1) {
      const cur = messages.value[currentAssistantIndex]
      if (cur && cur.streaming) {
        cur.content += '\n\n（已停止生成）'
        cur.streaming = false
      }
    }
    loading.value = false
  }

  function clearMessages() {
    messages.value = []
    sessionId.value = null
    currentAssistantIndex = -1
    agentSteps.value = []
    currentRequestId = null
  }

  function destroy() {
    stopGenerating()
  }

  return {
    messages,
    sessionId,
    loading,
    agentSteps,
    lastTrace,
    sendTextMessage,
    stopGenerating,
    clearMessages,
    destroy,
  }
})
