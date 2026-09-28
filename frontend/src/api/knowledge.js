import request from './request'

export function uploadKnowledge(file, title, category, scenicArea, onUploadProgress) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('title', title)
  if (category) formData.append('category', category)
  if (scenicArea) formData.append('scenicArea', scenicArea)
  return request.post('/api/knowledge/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    onUploadProgress,
  })
}

export function processKnowledge(id) {
  return request.post(`/api/knowledge/process/${id}`)
}

export function getKnowledgeList(params) {
  return request.get('/api/knowledge/list', { params })
}

export function deleteKnowledge(id) {
  return request.delete(`/api/knowledge/${id}`)
}

export function reprocessKnowledge(id) {
  return request.post(`/api/knowledge/reprocess/${id}`)
}

export function updateKnowledge(id, data) {
  return request.put(`/api/knowledge/${id}`, data)
}
