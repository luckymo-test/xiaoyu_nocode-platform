import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useUserStore } from '@/stores/user'
import { appMessage as message } from '@/utils/feedback'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { requiresAuth: true, title: '首页' },
    },
    {
      path: '/user/login',
      name: 'userLogin',
      component: () => import('@/views/user/UserLoginPage.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/user/register',
      name: 'userRegister',
      component: () => import('@/views/user/UserRegisterPage.vue'),
      meta: { title: '注册' },
    },
    {
      path: '/user/center',
      name: 'userCenter',
      component: () => import('@/views/user/UserCenterPage.vue'),
      meta: { requiresAuth: true, title: '个人中心' },
    },
    {
      path: '/app/gen/:id',
      name: 'appGenerate',
      component: () => import('@/views/app/AppGeneratePage.vue'),
      meta: { requiresAuth: true, title: '应用生成' },
    },
    {
      path: '/app/edit/:id',
      name: 'appEdit',
      component: () => import('@/views/app/AppEditPage.vue'),
      meta: { requiresAuth: true, title: '编辑应用' },
    },
    {
      path: '/projects',
      name: 'projects',
      component: () => import('@/views/projects/MyProjectsPage.vue'),
      meta: { requiresAuth: true, title: '我的项目' },
    },
    {
      path: '/templates',
      name: 'templates',
      component: () => import('@/views/projects/ProjectTemplatesPage.vue'),
      meta: { title: '模板市场' },
    },
    {
      path: '/docs',
      name: 'docs',
      component: () => import('@/views/ComingSoonPage.vue'),
      meta: { title: '文档' },
    },
    {
      path: '/admin/user',
      name: 'adminUser',
      component: () => import('@/views/admin/AdminUserPage.vue'),
      meta: { requiresAdmin: true, title: '用户管理' },
    },
    {
      path: '/admin/app',
      name: 'adminApp',
      component: () => import('@/views/admin/AdminAppPage.vue'),
      meta: { requiresAdmin: true, title: '应用管理' },
    },
    {
      path: '/admin/chat',
      name: 'adminChat',
      component: () => import('@/views/admin/AdminChatPage.vue'),
      meta: { requiresAdmin: true, title: '对话管理' },
    },
  ],
})

const BASE_TITLE = '小玉零代码平台'

// 路由变化时同步浏览器标题，方便多标签页区分
router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · ${BASE_TITLE}` : BASE_TITLE
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
