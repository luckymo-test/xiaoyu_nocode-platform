import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getLoginUser, userLogout } from '@/api/userController'
import { appMessage as message } from '@/utils/feedback'

export const useUserStore = defineStore('user', () => {
  const loginUser = ref<API.LoginUserVO | null>(null)

  const setLoginUser = (user: API.LoginUserVO | null) => {
    loginUser.value = user
  }

  const fetchLoginUser = async () => {
    try {
      const res = await getLoginUser()
      if (res.data.code === 0 && res.data.data) {
        loginUser.value = res.data.data
      }
    } catch {
      // 未登录状态，忽略错误
    }
  }

  const logout = async () => {
    try {
      await userLogout()
    } finally {
      loginUser.value = null
      message.success('已退出登录')
    }
  }

  return { loginUser, setLoginUser, fetchLoginUser, logout }
})
