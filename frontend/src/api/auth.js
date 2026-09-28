import request from './request'

export function login(username, password) {
  return request.post('/api/auth/login', { username, password })
}

export function register(username, password, nickname) {
  return request.post('/api/auth/register', { username, password, nickname })
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
