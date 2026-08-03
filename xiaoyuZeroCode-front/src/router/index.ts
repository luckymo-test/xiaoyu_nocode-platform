import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useUserStore } from '@/stores/user'
import { message } from 'ant-design-vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/user/login',
      name: 'userLogin',
      component: () => import('@/views/user/UserLoginPage.vue'),
    },
    {
      path: '/user/register',
      name: 'userRegister',
      component: () => import('@/views/user/UserRegisterPage.vue'),
    },
    {
      path: '/user/center',
      name: 'userCenter',
      component: () => import('@/views/user/UserCenterPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin/user',
      name: 'adminUser',
      component: () => import('@/views/admin/AdminUserPage.vue'),
      meta: { requiresAdmin: true },
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()

  if (to.meta.requiresAuth && !userStore.loginUser) {
    message.warning('请先登录')
    next('/user/login')
    return
  }

  if (to.meta.requiresAdmin && userStore.loginUser?.userRole !== 'admin') {
    message.warning('无权限访问')
    next('/')
    return
  }

  next()
})

export default router
