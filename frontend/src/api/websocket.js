/**
 * WebSocket 管理模块
 * 负责与 Java 后端的 WebSocket 连接、消息收发
 *
 * 消息协议：
 *   发送: {type: "text", content, session_id} | {type: "audio", format, session_id} + binary
 *   接收: {type: "text_chunk"|"audio_chunk"|"asr_result"|"done"|"error", ...}
 */
const WS_BASE = import.meta.env.VITE_WS_URL || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/chat`

export class ChatWebSocket {
  constructor() {
    this.ws = null
    this.isConnected = false
    this.reconnectTimer = null
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = 5
    this.reconnectDelay = 2000

    // 事件回调
    this.onTextChunk = null
    this.onAudioChunk = null
    this.onAsrResult = null
    this.onDone = null
    this.onError = null
    this.onConnect = null
    this.onDisconnect = null
    this.onExpression = null
  }

  /**
   * 建立 WebSocket 连接（带 token）
   */
  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    const token = localStorage.getItem('token') || ''
    const url = token ? `${WS_BASE}?token=${token}` : WS_BASE
    this.ws = new WebSocket(url)

    this.ws.onopen = () => {
      console.log('[WS] 连接建立')
      this.isConnected = true
      this.reconnectAttempts = 0
      this.onConnect?.()
    }

    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        switch (data.type) {
          case 'text_chunk':
            this.onTextChunk?.(data.content)
            break
          case 'audio_chunk':
            this.onAudioChunk?.(data.audio, data.format)
            break
          case 'asr_result':
            this.onAsrResult?.(data.text)
            break
          case 'done':
            this.onDone?.(data)
            break
          case 'error':
            this.onError?.(data.message)
            break
          case 'expression':
            this.onExpression?.(data.expression)
            break
          default:
            console.warn('[WS] 未知消息类型:', data.type)
        }
      } catch (e) {
        console.error('[WS] 解析消息失败:', e)
      }
    }

    this.ws.onclose = (event) => {
      console.log('[WS] 连接关闭:', event.code, event.reason)
      this.isConnected = false
      this.onDisconnect?.()
      this._tryReconnect()
    }

    this.ws.onerror = (error) => {
      console.error('[WS] 连接错误:', error)
    }
  }

  /**
   * 发送文字消息
   */
  sendText(text, sessionId = null) {
    if (!this._checkConnection()) return false

    this.ws.send(JSON.stringify({
      type: 'text',
      content: text,
      session_id: sessionId,
    }))
    return true
  }

  /**
   * 发送音频数据
   * @param {Blob} audioBlob - 音频 Blob
   * @param {string} format - 音频格式 (webm/wav/mp3)
   * @param {string} sessionId - 会话ID
   */
  sendAudio(audioBlob, format = 'webm', sessionId = null) {
    if (!this._checkConnection()) return false

    // 先发送元数据 JSON
    this.ws.send(JSON.stringify({
      type: 'audio',
      format: format,
      session_id: sessionId,
    }))

    // 再发送二进制音频数据
    audioBlob.arrayBuffer().then((buffer) => {
      this.ws.send(buffer)
    })

    return true
  }

  /**
   * 断开连接
   */
  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.isConnected = false
  }

  /**
   * 检查连接状态
   */
  _checkConnection() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[WS] 连接未就绪，尝试重连...')
      this.connect()
      return false
    }
    return true
  }

  /**
   * 自动重连
   */
  _tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WS] 重连次数超限，停止重连')
      return
    }

    this.reconnectAttempts++
    console.log(`[WS] ${this.reconnectDelay}ms 后尝试第 ${this.reconnectAttempts} 次重连...`)

    this.reconnectTimer = setTimeout(() => {
      this.connect()
    }, this.reconnectDelay)
  }
}

// 全局单例
export const chatWs = new ChatWebSocket()
