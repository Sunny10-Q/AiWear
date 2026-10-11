/**
 * Axios 封装（学生演示项目）
 *
 * - baseURL 为 '/api'，开发时由 Vite 代理到 .env.development 中的 PROXY_TARGET，避免跨域。
 * - 请求拦截器：从 Pinia authStore 取 token，自动附加 Authorization: Bearer <token>，需登录的接口无需在业务里手写。
 * - 响应成功但 code !== 200：视为业务错误，reject 一个带 message 的 Error，并挂上 err.response 便于页面取后端信息。
 * - 响应失败（网络错误、4xx/5xx）：统一补全 error.message，再 reject，页面用 ElMessage 展示即可。
 */
import axios from 'axios'
import { useAuthStore } from '../store/auth'
import router from '../router'

const BASE_URL = '/api'
const DEFAULT_TIMEOUT = 60000

const http = axios.create({
  baseURL: BASE_URL,
  timeout: DEFAULT_TIMEOUT,
})

/** 请求拦截：为需登录接口自动带上 token */
http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth?.token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

/** 响应拦截：统一处理业务错误与网络错误 */
http.interceptors.response.use(
  (resp) => {
    const data = resp.data
    if (data && typeof data.code === 'number' && data.code !== 200) {
      // JWT 失效：直接回登录页
      const message = typeof data.message === 'string' ? data.message : ''
      const isJwtExpired =
        // 旧后端文案（中文）
        (data.code === 401 && message === 'JWT无效或已过期') ||
        // 新后端返回示例（英文）
        (data.code === 500 && /jwt\s*expired/i.test(message)) ||
        // 兜底：只要包含 jwt + expired/过期 就当作过期处理
        (/jwt/i.test(message) && /(expired|过期)/i.test(message))
      if (isJwtExpired) {
        const auth = useAuthStore()
        auth.clear()
        if (router.currentRoute?.value?.name !== 'login') {
          router.replace({ name: 'login' })
        }
      }
      const err = new Error(data.message || '请求出错')
      err.response = resp
      return Promise.reject(err)
    }
    return resp
  },
  (error) => {
    const msg = error?.response?.data?.message || error?.message || '请求失败'
    if (!error.message) error.message = msg
    // 兜底：若后端以 HTTP 非 2xx 返回，axios 会走这里
    const responseData = error?.response?.data
    const message = typeof responseData?.message === 'string' ? responseData.message : msg
    const isJwtExpired =
      (typeof responseData?.code === 'number' && responseData.code === 401 && message === 'JWT无效或已过期') ||
      /jwt\s*expired/i.test(message) ||
      (/jwt/i.test(message) && /(expired|过期)/i.test(message))
    if (isJwtExpired) {
      const auth = useAuthStore()
      auth.clear()
      if (router.currentRoute?.value?.name !== 'login') {
        router.replace({ name: 'login' })
      }
    }
    return Promise.reject(error)
  }
)

export default http

