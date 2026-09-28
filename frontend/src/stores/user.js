import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login, register, getMe } from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))
  const token = ref(localStorage.getItem('token') || '')

  function setAuth(data) {
    token.value = data.token
    user.value = data.user
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(data.user))
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
  }

  const isLoggedIn = () => !!token.value
  const isAdmin = () => user.value?.role === 'admin'

  return { user, token, loginUser, registerUser, fetchMe, logout, isLoggedIn, isAdmin, setAuth }
})
