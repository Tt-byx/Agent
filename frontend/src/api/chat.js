import request from './request'

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
