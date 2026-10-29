import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '',
  timeout: 60000,
})

// 请求拦截器：注入 token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/**
 * 401 处理：清理本地登录态并跳转登录页。
 *
 * 为什么要集中处理：后端所有需要鉴权的接口在 token 过期/缺失时统一返回 401
 * （见后端 SecurityConfig 的 authenticationEntryPoint）。
 * 如果每个调用点各写一遍，必然有遗漏 —— 用户会看到一堆"请求失败"却不知道要重新登录。
 *
 * 用 isRedirecting 做防抖：一个页面并发 5 个请求同时 401，只跳一次、只弹一次提示。
 */
let isRedirecting = false
function handleUnauthorized() {
  if (isRedirecting) return
  isRedirecting = true
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  ElMessage.warning('登录已过期，请重新登录')
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  setTimeout(() => {
    window.location.href = `/login?redirect=${redirect}`
    isRedirecting = false
  }, 600)
}

// 响应拦截器：统一处理返回格式
request.interceptors.response.use(
  (response) => {
    const { data } = response
    if (data.code === 200) {
      return data.data
    }
    // 业务层用 code 表达错误（HTTP 200 但 code != 200）
    if (data.code === 401) {
      handleUnauthorized()
    } else if (data.code === 429) {
      ElMessage.warning(data.message || '操作太频繁，请稍后再试')
    }
    return Promise.reject(new Error(data.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message

    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      ElMessage.error(message || '当前账号无权访问该资源')
    } else if (status === 429) {
      ElMessage.warning(message || '操作太频繁，请稍后再试')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else if (!error.response) {
      ElMessage.error('无法连接后端服务，请确认服务已启动')
    }
    return Promise.reject(error)
  }
)

export default request
