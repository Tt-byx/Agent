/**
 * 身份与幂等工具。
 *
 * 为什么需要 visitorId：后端的限流是「按身份」的 —— 传了 visitorId 就按 visitorId 限流，
 * 没传才退化为按客户端 IP 限流。同一办公室/校园网出口 IP 相同，纯按 IP 限流会互相拖累，
 * 所以前端应始终带上一个稳定的游客标识。
 *
 * 注意：这**不是**安全凭据，只是一个限流/隔离用的分组键。后端不会据此授予任何权限，
 * 真正的身份仍以 JWT 为准（见后端 ChatServiceImpl 的说明）。
 */

const VISITOR_KEY = 'scenic_visitor_id'
const VISITOR_TOKEN_KEY = 'scenic_visitor_token'

/** 生成一个 UUID v4（优先用浏览器原生 API，降级到随机串） */
export function uuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * 获取（或首次生成并持久化）游客标识。
 * 存 localStorage —— 只要用户不换浏览器、不清缓存，这个标识就稳定。
 */
export function getVisitorId() {
  try {
    let id = localStorage.getItem(VISITOR_KEY)
    if (!id) {
      id = 'v_' + uuid()
      localStorage.setItem(VISITOR_KEY, id)
    }
    return id
  } catch {
    // localStorage 不可用（隐私模式等）时，退化为进程内临时值，不阻断对话
    if (!getVisitorId._fallback) getVisitorId._fallback = 'v_' + uuid()
    return getVisitorId._fallback
  }
}

/**
 * 为一次「用户意图」生成幂等键。
 *
 * 语义：同一次发送（哪怕是网络重试）应复用同一个 requestId，这样后端只真正执行一次；
 * 而用户重新问一遍是**新的意图**，应生成新的 requestId。所以本函数由调用方
 * 在「用户点击发送」时调一次，把结果保存下来随重试一起用。
 */
export function newRequestId() {
  return 'req_' + uuid()
}

/* ───────────────────────── 游客凭证（后端签发的 JWT） ─────────────────────────
 *
 * 2026-11-03：这里原来只有前端自己生成的 visitorId，没有后端凭证。
 * 两者作用完全不同，别混：
 *   visitorId   —— 前端 UUID，只做限流分组，**不是凭据**
 *   visitorToken—— 后端 /api/auth/guest 签发的 JWT，决定长期记忆算谁的
 */

export function getStoredVisitorToken() {
  try {
    return localStorage.getItem(VISITOR_TOKEN_KEY)
  } catch {
    return null
  }
}

export function setStoredVisitorToken(token) {
  try {
    if (token) localStorage.setItem(VISITOR_TOKEN_KEY, token)
  } catch { /* 隐私模式忽略 */ }
}

/**
 * 登录成功 / 登出时调用：丢弃游客凭证。
 *
 * 不清的后果：同一浏览器先以游客 A 聊过 → 登录成用户 B → B 退出 →
 * 又变回「游客 A」，把 A 的偏好带了回来。用户会觉得"这系统记错人了"。
 */
export function clearVisitorToken() {
  try {
    localStorage.removeItem(VISITOR_TOKEN_KEY)
  } catch { /* 忽略 */ }
}
