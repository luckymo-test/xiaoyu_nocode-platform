import { useUserStore } from '@/stores/user'

export const isAdmin = () => {
  const userStore = useUserStore()
  return userStore.loginUser?.userRole === 'admin'
}
