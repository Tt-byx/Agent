import request from './request'
import axios from 'axios'

/**
 * 发送聊天消息
 * @param {string} message - 用户输入的消息
 * @param {string} [sessionId] - 会话ID
 * @returns {Promise<{reply: string, sessionId: string}>}
 */
export function sendChatMessage(message, sessionId) {
  return request.post('/api/chat', {
    message,
    sessionId,
  })
}

/**
 * 语音识别（ASR）— 直接调 Python 后端
 * @param {Blob} audioBlob - 音频 Blob
 * @param {string} format - 音频格式 (webm/wav)
 * @returns {Promise<{text: string, format: string}>}
 */
export function recognizeSpeech(audioBlob, format = 'webm') {
  const formData = new FormData()
  formData.append('audio', audioBlob, `audio.${format}`)

  return axios.post('/py-api/asr', formData, {
    timeout: 120000,  // ASR 首次加载模型需要较长时间
  }).then(res => res.data)
}
