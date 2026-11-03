import request from './request'

export function login(username, password) {
  return request.post('/api/auth/login', { username, password })
}

export function register(username, password, nickname) {
  return request.post('/api/auth/register', { username, password, nickname })
}

/**
 * 2026-11-03 · 游客凭证。
 *
 * 这是「游客免登录对话」的关键一环，之前前端完全没有实现 ——
 * 只有游客标识（`getVisitorId()` 生成的前端 UUID），没有后端签发的 JWT。
 *
 * 后果是：后端 `IdentityResolver` 拿不到 visitorToken 时，
 * 长期记忆的归属键会退化成 sessionId（见 `Identity.memoryKey`），
 * 于是**跨会话记忆对游客失效** —— 而这是项目的核心能力之一。
 */
export function getGuestToken() {
  return request.post('/api/auth/guest')
}

export function getMe() {
  return request.get('/api/auth/me')
}

export function getConversations() {
  return request.get('/api/conversations')
}

export function getConversationMessages(id) {
  return request.get(`/api/conversations/${id}/messages`)
}

export function deleteConversation(id) {
  return request.delete(`/api/conversations/${id}`)
}

export function renameConversation(id, title) {
  return request.put(`/api/conversations/${id}/title`, { title })
}

export function searchConversations({ keyword, startDate, endDate } = {}) {
  const params = {}
  if (keyword) params.keyword = keyword
  if (startDate) params.startDate = startDate
  if (endDate) params.endDate = endDate
  return request.get('/api/conversations/search', { params })
}
