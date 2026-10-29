import request from './request'
import { getVisitorId } from '@/utils/identity'

/**
 * 发送聊天消息（整段返回）
 * @param {string} message - 用户输入的消息
 * @param {string} [sessionId] - 会话ID
 * @param {string} [requestId] - 幂等键；同一身份 + 同一 requestId 重复提交只会真正执行一次
 * @returns {Promise<{reply: string, sessionId: string}>}
 */
export function sendChatMessage(message, sessionId, requestId) {
  return request.post('/api/chat', {
    message,
    sessionId,
    requestId,
    visitorId: getVisitorId(),
  })
}

/**
 * 流式对话（SSE · Wave 5）。
 *
 * 为什么不用 axios：SSE 需要「边收边读」，axios 默认等响应体收完才 resolve，
 * 拿不到增量。这里用 fetch + ReadableStream，逐事件解析。
 *
 * 服务端事件协议（见后端 ChatController）：
 *   meta    {traceId}                     首个事件，便于报障反查
 *   session {sessionId}                   会话标识（首次对话由服务端生成，必须存下来复用）
 *   step    {step, phase, usedTools?}     步骤边界（ReAct 每步 start/end）
 *   token   <纯文本>                      正式回答的增量
 *   discard {step}                        撤销该步已显示的文本（该步其实在调工具）
 *   done    <完整答案>                    最终完整答案（可据此校验拼接结果）
 *   error   <提示文本>                    失败（此后不会再有 done）
 *
 * @param {string} message
 * @param {string} sessionId
 * @param {string} requestId
 * @param {object} handlers - { onMeta, onSession, onStep, onToken, onDiscard, onDone, onError, onSessionReset }
 * @param {AbortSignal} [signal] - 用于「停止生成」
 * @param {object} [opts]
 * @param {boolean} [opts.allowSessionReset=true] - 会话归属失效时是否允许「丢弃旧会话 + 自动以新会话重发」
 */
export async function streamChatMessage(message, sessionId, requestId, handlers = {}, signal, opts = {}) {
  const { onMeta, onSession, onStep, onToken, onDiscard, onDone, onError } = handlers
  const allowReset = opts.allowSessionReset !== false

  let resp
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(localStorage.getItem('token')
          ? { Authorization: `Bearer ${localStorage.getItem('token')}` }
          : {}),
      },
      body: JSON.stringify({ message, sessionId, requestId, visitorId: getVisitorId() }),
      signal,
    })
  } catch (e) {
    if (e?.name === 'AbortError') return
    onError?.('网络连接失败，请检查后端服务是否已启动')
    return
  }

  if (!resp.ok) {
    onError?.(resp.status === 429 ? '提问有点频繁，请稍后再试～' : `请求失败（HTTP ${resp.status}）`)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let full = ''

  // 会话归属失效：本地残留的 sessionId 可能属于上一次登录的账号（或游客会话），
  // 后端会回 error「该会话不属于当前账号」。这不是用户该理解的错误 ——
  // 丢弃旧会话、以新会话自动重发一次，用户无感。
  let pendingSessionReset = false

  const handleEvent = (name, data) => {
    switch (name) {
      case 'meta':
        try { onMeta?.(JSON.parse(data)) } catch { /* 忽略格式异常 */ }
        break
      case 'session':
        try { onSession?.(JSON.parse(data)) } catch { /* 忽略 */ }
        break
      case 'step':
        try { onStep?.(JSON.parse(data)) } catch { /* 忽略 */ }
        break
      case 'discard':
        try { onDiscard?.(JSON.parse(data)) } catch { /* 忽略 */ }
        break
      case 'token':
        full += data
        onToken?.(data)
        break
      case 'done':
        onDone?.(data, full)
        break
      case 'error': {
        const isOwnershipError =
          typeof data === 'string' &&
          (data.includes('不属于当前账号') || data.includes('无权访问'))
        if (isOwnershipError && sessionId && allowReset) {
          pendingSessionReset = true
          return // 不向上层报错，改为静默自愈
        }
        onError?.(data)
        break
      }
      default:
        break
    }
  }

  // SSE 以「空行」分隔事件；逐块读取后按行解析，未闭合的行留在 buffer 里
  const flushLines = (text) => {
    const lines = text.split('\n')
    const rest = lines.pop() // 最后一行可能不完整，留到下轮
    let eventName = null
    let dataLine = ''
    for (const raw of lines) {
      const line = raw.replace(/\r$/, '')
      if (line === '') {
        if (eventName) handleEvent(eventName, dataLine)
        eventName = null
        dataLine = ''
        continue
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLine += line.slice(5).replace(/^ /, '')
      }
    }
    return rest
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = flushLines(buffer)
    }
    if (buffer.trim()) flushLines(buffer + '\n')
  } catch (e) {
    if (e?.name !== 'AbortError') onError?.('数据流中断，请重试')
    return
  }

  // 会话失效自愈：清掉上层持有的旧 sessionId，以全新会话重发（只自愈一次）
  if (pendingSessionReset) {
    handlers.onSessionReset?.()
    await streamChatMessage(message, null, requestId, handlers, signal, {
      allowSessionReset: false,
    })
  }
}
