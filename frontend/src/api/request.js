import axios from 'axios'

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

// 响应拦截器：统一处理返回格式
request.interceptors.response.use(
  (response) => {
    const { data } = response
    console.log('响应数据:', data)
    if (data.code === 200) {
      return data.data
    }
    return Promise.reject(new Error(data.message || '请求失败'))
  },
  (error) => {
    console.error('请求错误:', error.message, error)
    return Promise.reject(error)
  }
)

export default request
