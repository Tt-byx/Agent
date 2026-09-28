import request from './request'

export function getOverview() {
  return request.get('/api/dashboard/overview')
}

export function getSentiment() {
  return request.get('/api/dashboard/sentiment')
}

export function getTrends() {
  return request.get('/api/dashboard/trends')
}

export function getConversations() {
  return request.get('/api/dashboard/conversations')
}

export function getTopQuestions() {
  return request.get('/api/dashboard/top-questions')
}
