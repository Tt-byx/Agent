import request from './request'

/**
 * Wave 4 · 成本 / 延迟 / 可靠性台账（仅管理员可见）。
 *
 * 数据来自服务端进程内 Micrometer 计数，**重启归零**；
 * 长期趋势应以 Prometheus 为准（接口返回里也带了这句说明）。
 */
export function getOpsLedger() {
  return request.get('/api/ops/ledger')
}
