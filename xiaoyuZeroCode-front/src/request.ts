import axios from 'axios'
import { appMessage as message } from '@/utils/feedback'

// 开发环境走 vite proxy（相对路径 /api）；部署时用 .env 的 VITE_API_BASE_URL 覆盖
export const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

const myAxios = axios.create({
  baseURL: BASE_URL,
  timeout: 60000,
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
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
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        window.location.href = `/user/login?redirect=${window.location.href}`
      }
    }
    return response
  },
  function (error) {
    return Promise.reject(error)
  },
)

export default myAxios
