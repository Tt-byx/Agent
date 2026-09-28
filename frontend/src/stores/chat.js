import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatWs } from '@/api/websocket'
import { AudioPlayer } from '@/components/chat/AudioPlayer'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const sessionId = ref(null)
  const loading = ref(false)
  const isRecording = ref(false)
  const isWsConnected = ref(false)
  const audioPlaying = ref(false)
  const currentExpression = ref('Normal')

  // 音频播放器
  const audioPlayer = new AudioPlayer()

  // 当前正在流式接收的助手消息索引
  let currentAssistantIndex = -1

  // 表情直接回调（由 ChatView 注册，跳过 watcher 时序问题）
  let _expressionCallback = null
  function setExpressionCallback(cb) {
    _expressionCallback = cb
  }

  /**
   * 初始化 WebSocket 连接
   */
  function initWebSocket() {
    chatWs.onConnect = () => {
      isWsConnected.value = true
      audioPlayer.init()
    }

    chatWs.onDisconnect = () => {
      isWsConnected.value = false
    }

    chatWs.onAsrResult = (text) => {
      const lastUserMsg = [...messages.value].reverse().find(m => m.role === 'user')
      if (lastUserMsg && lastUserMsg.isVoice) {
        lastUserMsg.content = `🎤 ${text}`
      }
    }

    // ??????????????
    let _currentSentenceText = ''

    chatWs.onTextChunk = (text) => {
      if (currentAssistantIndex === -1) {
        currentAssistantIndex = messages.value.length
        messages.value.push({
          role: 'assistant',
          content: '',
          timestamp: Date.now(),
        })
      }
      messages.value[currentAssistantIndex].content += text
      // ?????????????
      _currentSentenceText += text
      const endMarks = ['?', '?', '?', '?', '\n']
      if (endMarks.some(m => text.includes(m))) {
        // ????????????????
        _currentSentenceText = _currentSentenceText.trim()
      }
    }

    chatWs.onAudioChunk = (audioBase64, format) => {
      console.log('[Audio] chunk received, format:', format, 'size:', audioBase64?.length)
      const sentenceForLipSync = _currentSentenceText
      _currentSentenceText = ''
      audioPlayer._pendingLipText = sentenceForLipSync
      audioPlayer.enqueue(audioBase64, format || 'wav')
    }

    chatWs.onExpression = (expression) => {
      currentExpression.value = expression
      if (_expressionCallback) {
        _expressionCallback(expression)
      }
    }

    chatWs.onDone = (data) => {
      loading.value = false
      _currentSentenceText = ''
      if (data.session_id) {
        sessionId.value = data.session_id
      }
      currentAssistantIndex = -1
    }

    chatWs.onError = (errMsg) => {
      loading.value = false
      if (currentAssistantIndex === -1) {
        messages.value.push({
          role: 'assistant',
          content: `抱歉，${errMsg || '暂时无法回答，请稍后再试。'}`,
          timestamp: Date.now(),
          isError: true,
        })
      } else {
        messages.value[currentAssistantIndex].content += `\n\n[错误: ${errMsg}]`
      }
      currentAssistantIndex = -1
    }

    chatWs.connect()
  }

  function sendTextMessage(content) {
    if (!content.trim() || loading.value) return

    messages.value.push({
      role: 'user',
      content,
      timestamp: Date.now(),
      isVoice: false,
    })

    loading.value = true
    currentAssistantIndex = -1

    chatWs.sendText(content, sessionId.value)
  }

  function sendVoiceMessage(audioBlob, format) {
    if (!audioBlob || loading.value) return

    messages.value.push({
      role: 'user',
      content: '🎤 正在识别...',
      timestamp: Date.now(),
      isVoice: true,
    })

    loading.value = true
    currentAssistantIndex = -1

    chatWs.sendAudio(audioBlob, format, sessionId.value)
  }

  function clearMessages() {
    messages.value = []
    sessionId.value = null
    currentAssistantIndex = -1
    audioPlayer.stop()
    audioPlaying.value = false
  }

  function destroy() {
    chatWs.disconnect()
    audioPlayer.destroy()
  }

  // AudioPlayer 回调链 — 管理 audioPlaying 状态 + 通知外部监听器
  let _onAudioStartListeners = []
  let _onAudioEndListeners = []

  function onAudioStart(cb) { _onAudioStartListeners.push(cb) }
  function onAudioEnd(cb) { _onAudioEndListeners.push(cb) }

  // ????????
  const lipSyncText = ref('')
  const lipSyncDuration = ref(0)

  audioPlayer.onPlayStart = (duration) => {
    audioPlaying.value = true
    // ??????????
    const text = audioPlayer._pendingLipText || ''
    audioPlayer._pendingLipText = ''
    if (text) {
      lipSyncText.value = text
      lipSyncDuration.value = (duration && duration > 0) ? duration * 1000 : Math.max(text.length * 180, 2000)
    }
    _onAudioStartListeners.forEach(cb => cb())
  }
  audioPlayer.onPlayEnd = () => {
    audioPlaying.value = false
    _onAudioEndListeners.forEach(cb => cb())
  }

  return {
    messages,
    sessionId,
    loading,
    isRecording,
    isWsConnected,
    audioPlaying,
    currentExpression,
    lipSyncText,
    lipSyncDuration,
    audioPlayer,
    initWebSocket,
    sendTextMessage,
    sendVoiceMessage,
    clearMessages,
    destroy,
    setExpressionCallback,
    onAudioStart,
    onAudioEnd,
  }
})
