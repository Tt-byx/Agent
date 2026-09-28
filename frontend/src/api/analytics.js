import request from './request'

/** 导入 xlsx 数据 */
export function importAnalyticsData(filePath = '') {
  return request.post(`/api/analytics/import?filePath=${encodeURIComponent(filePath)}`)
}

/** 按月消费趋势 */
export function getConsumptionTrend() {
  return request.get('/api/analytics/consumption')
}

/** 游客画像 */
export function getVisitorProfile() {
  return request.get('/api/analytics/visitor-profile')
}

/** 满意度分布 */
export function getSatisfaction() {
  return request.get('/api/analytics/satisfaction')
}

/** 按月客流统计 */
export function getPeakPeriods() {
  return request.get('/api/analytics/peak-periods')
}

/** 热门景点排行 */
export function getTopAttractions() {
  return request.get('/api/analytics/attractions')
}

/** 提交反馈 */
export function submitFeedback(messageId, type) {
  return request.post('/api/feedback', { messageId, type })
}

/** 反馈统计 */
export function getFeedbackStats() {
  return request.get('/api/feedback/stats')
}

/** 游客画像列表 */
export function getVisitorProfiles() {
  return request.get('/api/visitor-profiles')
}

/** 分页筛选行为数据 */
export function getFilteredAnalyticsData(params) {
  return request.get('/api/analytics/data', { params })
}

/** 营销分析报告数据 */
export function getMarketingReport() {
  return request.get('/api/analytics/report')
}
