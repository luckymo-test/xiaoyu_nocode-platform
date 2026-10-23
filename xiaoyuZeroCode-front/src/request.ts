import axios from 'axios'
import { appMessage as message } from '@/utils/feedback'
import { CSRF_HEADER, ensureCsrfToken, getCsrfToken } from '@/utils/csrf'

// 开发环境走 vite proxy（相对路径 /api）；部署时用 .env 的 VITE_API_BASE_URL 覆盖
export const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const myAxios = axios.create({
  baseURL: BASE_URL,
  timeout: 60000,
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  async function (config) {
    const method = (config.method || 'get').toLowerCase()
    if (!['get', 'head', 'options', 'trace'].includes(method)) {
      await ensureCsrfToken(BASE_URL)
    }
    const csrfToken = getCsrfToken()
    if (csrfToken) {
      config.headers[CSRF_HEADER] = csrfToken
    }
    return config
  },
  function (error) {
    return Promise.reject(error)
  },
)

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      handleUnauthorized(response.request.responseURL)
    }
    return response
  },
  function (error) {
    const status = error.response?.status
    const code = error.response?.data?.code
    if (status === 401 || code === 40100) {
      handleUnauthorized(error.config?.url)
    }
    return Promise.reject(error)
  },
)

export function handleUnauthorized(responseUrl?: string) {
  if (
    responseUrl?.includes('user/get/login') ||
    window.location.pathname.includes('/user/login')
  ) {
    return
  }
  message.warning('请先登录')
  window.location.href = `/user/login?redirect=${window.location.href}`
}

export default myAxios
