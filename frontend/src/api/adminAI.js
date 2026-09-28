import request from './request'

/** 运营人员 AI 问答 */
export function adminAIChat(message) {
  return request.post('/api/admin-ai/chat', { message })
}

/** 基于画像生成营销推荐 */
export function adminAIRecommend(userId) {
  return request.post('/api/admin-ai/recommend', { userId })
}

/** 获取游客列表（供选择推荐） */
export function getAdminAIVisitors() {
  return request.get('/api/admin-ai/visitors')
}
