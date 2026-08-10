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
      meta: { requiresAuth: true },
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
      path: '/app/gen/:id',
      name: 'appGenerate',
      component: () => import('@/views/app/AppGeneratePage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/app/edit/:id',
      name: 'appEdit',
      component: () => import('@/views/app/AppEditPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/projects',
      name: 'projects',
      component: () => import('@/views/projects/MyProjectsPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin/user',
      name: 'adminUser',
      component: () => import('@/views/admin/AdminUserPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/app',
      name: 'adminApp',
      component: () => import('@/views/admin/AdminAppPage.vue'),
      meta: { requiresAdmin: true },
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()
  const needAuth = to.meta.requiresAuth || to.meta.requiresAdmin

  // 首次进入受保护页面时，登录态可能还没从后端拉取，先同步一次再判断
  if (needAuth && !userStore.loginUser) {
    await userStore.fetchLoginUser()
  }

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
