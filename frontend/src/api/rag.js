import request from './request'

/**
 * Wave 6 · RAG 运维接口（仅管理员可见）。
 */

/** 向量库对账：比对数据库记录条数与实际向量条数，返回不一致明细 */
export function reconcile() {
  return request.get('/api/rag/reconcile')
}

/** 检索探针：给定问题，看召回了哪些切片（调参/排障用）。注意后端是 @RequestParam query */
export function probe(query) {
  return request.post('/api/rag/probe', null, { params: { query } })
}

/** 重跑离线评测集，返回 Hit@1 / MRR 等指标 */
export function runEval() {
  return request.post('/api/rag/eval')
}

/** 对抗样本集：检验检索对"越界/诱导"问题的处理 */
export function adversarial() {
  return request.get('/api/rag/adversarial')
}

/** 重建 BM25 索引（不重新生成向量，仅刷新关键词索引） */
export function reloadIndex() {
  return request.post('/api/rag/reload-index')
}
