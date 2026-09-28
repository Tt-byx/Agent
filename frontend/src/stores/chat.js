import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatWs } from '@/api/websocket'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const sessionId = ref(null)
  const loading = ref(false)
  const isWsConnected = ref(false)

  // 当前正在流式接收的助手消息索引
  let currentAssistantIndex = -1

  /**
   * 初始化 WebSocket 连接
   */
  function initWebSocket() {
    chatWs.onConnect = () => {
      isWsConnected.value = true
    }

    chatWs.onDisconnect = () => {
      isWsConnected.value = false
    }

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
    }

    chatWs.onDone = (data) => {
      loading.value = false
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
    })

    loading.value = true
    currentAssistantIndex = -1

    chatWs.sendText(content, sessionId.value)
  }

  function clearMessages() {
    messages.value = []
    sessionId.value = null
    currentAssistantIndex = -1
  }

  function destroy() {
    chatWs.disconnect()
  }

  return {
    messages,
    sessionId,
    loading,
    isWsConnected,
    initWebSocket,
    sendTextMessage,
    clearMessages,
    destroy,
  }
})
