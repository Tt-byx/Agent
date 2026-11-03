import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login, register, getMe } from '@/api/auth'
import { useChatStore } from '@/stores/chat'
import { clearVisitorToken } from '@/utils/identity'

export const useUserStore = defineStore('user', () => {
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))
  const token = ref(localStorage.getItem('token') || '')

  /**
   * 账号切换时必须重置对话上下文。
   *
   * 原因：sessionId 是「会话归属」的凭据。A 账号退出、B 账号登录后，
   * 若内存里仍留着 A 的 sessionId，B 发消息会被后端判为
   * 「该会话不属于当前账号」而拒绝。与其等报错，不如在登录/登出瞬间就清掉。
   */
  function resetChatContext() {
    try {
      useChatStore().clearMessages()
    } catch {
      /* store 尚未初始化时忽略 */
    }
  }

  function setAuth(data) {
    token.value = data.token
    user.value = data.user
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(data.user))
    // 2026-11-03：登录后丢弃游客凭证。后端虽然优先用 user:{id}，
    // 但留着这张票，等用户登出后又会被当成原游客，把旧偏好带回来。
    clearVisitorToken()
    resetChatContext()
  }

  async function loginUser(username, password) {
    const data = await login(username, password)
    setAuth(data)
    return data
  }

  async function registerUser(username, password, nickname) {
    const data = await register(username, password, nickname)
    setAuth(data)
    return data
  }

  async function fetchMe() {
    if (!token.value) return null
    try {
      const data = await getMe()
      user.value = data
      localStorage.setItem('user', JSON.stringify(data))
      return data
    } catch {
      logout()
      return null
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    // 登出即回到游客态：清掉旧凭证，下次进对话页会重新领一张新身份，
    // 不会继承上一个账号/上一个游客的记忆。
    clearVisitorToken()
    resetChatContext()
  }

  const isLoggedIn = () => !!token.value
  const isAdmin = () => user.value?.role === 'admin'

  return { user, token, loginUser, registerUser, fetchMe, logout, isLoggedIn, isAdmin, setAuth }
})
