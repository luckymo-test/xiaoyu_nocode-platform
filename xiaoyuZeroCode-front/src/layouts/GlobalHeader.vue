<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  CloseOutlined,
  DownOutlined,
  FolderOpenOutlined,
  LogoutOutlined,
  MenuOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { useUserStore } from '@/stores/user'
import type { MenuItem } from '@/config/menu'

const props = defineProps<{
  menuItems: MenuItem[]
}>()

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 主菜单（所有人可见） */
const mainItems = computed(() => props.menuItems.filter((item) => item.role !== 'admin'))
/** 管理菜单（仅管理员可见，收进"管理"下拉，避免导航过挤） */
const adminItems = computed(() =>
  props.menuItems.filter(
    (item) => item.role === 'admin' && userStore.loginUser?.userRole === 'admin',
  ),
)
const allItems = computed(() => [...mainItems.value, ...adminItems.value])

/** 高亮跟随路由而不是跟随点击：返回 / 重定向后也能正确高亮 */
const selectedKeys = computed<string[]>(() => {
  const path = route.path
  if (path.startsWith('/app/')) return ['projects']
  const hit = props.menuItems.find((item) => item.path === path)
  return hit ? [hit.key] : []
})

const go = (path: string) => {
  if (route.path !== path) router.push(path)
}

const handleLogout = async () => {
  await userStore.logout()
  router.push('/')
}

const handleUserMenuClick = ({ key }: { key: string | number }) => {
  if (key === 'center') go('/user/center')
  if (key === 'projects') go('/projects')
  if (key === 'logout') handleLogout()
}

/* 移动端抽屉 */
const drawerOpen = ref(false)
watch(
  () => route.fullPath,
  () => {
    drawerOpen.value = false
  },
)

/* 滚动后给导航补描边与阴影 */
const scrolled = ref(false)
const onScroll = () => {
  scrolled.value = window.scrollY > 8
}
onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <header class="global-header" :class="{ 'is-scrolled': scrolled }">
    <div class="header-inner">
      <button class="brand" type="button" aria-label="回到首页" @click="go('/')">
        <img src="/logo.svg" alt="" class="brand-logo" />
        <span class="brand-name">小玉零代码</span>
        <span class="brand-badge">AI</span>
      </button>

      <nav class="header-nav">
        <a-menu class="nav-menu" mode="horizontal" :selected-keys="selectedKeys">
          <a-menu-item v-for="item in mainItems" :key="item.key" @click="go(item.path)">
            <template #icon>
              <component :is="item.icon" />
            </template>
            {{ item.label }}
          </a-menu-item>
          <a-sub-menu v-if="adminItems.length" key="adminGroup">
            <template #icon>
              <SettingOutlined />
            </template>
            <template #title>管理</template>
            <a-menu-item v-for="item in adminItems" :key="item.key" @click="go(item.path)">
              <template #icon>
                <component :is="item.icon" />
              </template>
              {{ item.label }}
            </a-menu-item>
          </a-sub-menu>
        </a-menu>
      </nav>

      <div class="header-right">
        <template v-if="userStore.loginUser">
          <a-dropdown placement="bottomRight">
            <button class="user-chip" type="button">
              <a-avatar :src="userStore.loginUser.userAvatar" :size="28">
                {{ userStore.loginUser.userName?.charAt(0) || 'U' }}
              </a-avatar>
              <span class="user-name">{{ userStore.loginUser.userName }}</span>
              <DownOutlined class="user-caret" />
            </button>
            <template #overlay>
              <a-menu class="user-menu" @click="handleUserMenuClick">
                <a-menu-item key="center">
                  <UserOutlined />
                  <span>个人中心</span>
                </a-menu-item>
                <a-menu-item key="projects">
                  <FolderOpenOutlined />
                  <span>我的项目</span>
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" danger>
                  <LogoutOutlined />
                  <span>退出登录</span>
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <a-button type="text" @click="go('/user/login')">登录</a-button>
          <a-button type="primary" @click="go('/user/register')">免费注册</a-button>
        </template>

        <button
          class="nav-toggle"
          type="button"
          aria-label="打开导航菜单"
          @click="drawerOpen = true"
        >
          <MenuOutlined />
        </button>
      </div>
    </div>

    <a-drawer v-model:open="drawerOpen" placement="right" :width="286" :closable="false">
      <div class="drawer-head">
        <button class="brand" type="button" @click="go('/')">
          <img src="/logo.svg" alt="" class="brand-logo" />
          <span class="brand-name">小玉零代码</span>
        </button>
        <button
          class="drawer-close"
          type="button"
          aria-label="关闭导航菜单"
          @click="drawerOpen = false"
        >
          <CloseOutlined />
        </button>
      </div>
      <a-menu class="drawer-menu" mode="inline" :selected-keys="selectedKeys">
        <a-menu-item v-for="item in allItems" :key="item.key" @click="go(item.path)">
          <template #icon>
            <component :is="item.icon" />
          </template>
          {{ item.label }}
        </a-menu-item>
      </a-menu>
      <div class="drawer-foot">
        <template v-if="userStore.loginUser">
          <a-button type="text" block @click="go('/user/center')">个人中心</a-button>
          <a-button danger block @click="handleLogout">退出登录</a-button>
        </template>
        <template v-else>
          <a-button block @click="go('/user/login')">登录</a-button>
          <a-button type="primary" block @click="go('/user/register')">免费注册</a-button>
        </template>
      </div>
    </a-drawer>
  </header>
</template>

<style scoped>
.global-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: var(--header-h);
  background: var(--bg-glass);
  border-bottom: 1px solid transparent;
  backdrop-filter: blur(14px) saturate(160%);
  -webkit-backdrop-filter: blur(14px) saturate(160%);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.global-header.is-scrolled {
  border-bottom-color: var(--border);
  background: var(--bg-glass-strong);
  box-shadow: var(--shadow-1);
}

.header-inner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 100%;
  max-width: var(--page-max);
  margin: 0 auto;
  padding: 0 var(--main-pad);
}

/* ---------- 品牌 ---------- */
.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.brand:hover {
  background: rgba(79, 107, 255, 0.08);
}

.brand-logo {
  display: block;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  box-shadow: var(--shadow-glow);
}

.brand-name {
  color: var(--text-1);
  font-size: var(--font-lg);
  font-weight: 700;
  letter-spacing: 0.4px;
  white-space: nowrap;
}

.brand-badge {
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--gradient-brand);
  color: var(--text-inverse);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
}

/* ---------- 导航 ---------- */
.header-nav {
  flex: 1;
  min-width: 0;
  margin-left: var(--space-2);
}

.nav-menu {
  border-bottom: none;
  background: transparent;
  line-height: normal;
}

.nav-menu :deep(.ant-menu-item),
.nav-menu :deep(.ant-menu-submenu-title) {
  display: inline-flex;
  align-items: center;
  height: 38px;
  line-height: 38px;
  margin-inline: 2px;
  padding-inline: 14px;
  border-radius: var(--radius-md);
  font-weight: 500;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.nav-menu :deep(.ant-menu-item::after),
.nav-menu :deep(.ant-menu-submenu::after) {
  display: none;
}

.nav-menu :deep(.ant-menu-item:hover),
.nav-menu :deep(.ant-menu-submenu-title:hover) {
  background: var(--bg-hover);
}

.nav-menu :deep(.ant-menu-item-selected) {
  background: var(--gradient-brand);
  color: var(--text-inverse);
  box-shadow: var(--shadow-glow);
}

.nav-menu :deep(.ant-menu-item-selected .anticon) {
  color: var(--text-inverse);
}

.nav-menu :deep(.ant-menu-submenu-selected > .ant-menu-submenu-title) {
  color: var(--brand-500);
  background: var(--gradient-brand-soft);
}

/* ---------- 右侧 ---------- */
.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-left: auto;
  flex-shrink: 0;
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 40px;
  padding: 0 10px 0 6px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  cursor: pointer;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.user-chip:hover {
  background: var(--bg-surface);
  border-color: var(--border);
  box-shadow: var(--shadow-1);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  color: var(--text-1);
  font-size: var(--font-base);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-caret {
  color: var(--text-3);
  font-size: 10px;
}

.user-menu :deep(.anticon) {
  margin-right: 8px;
}

.nav-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  color: var(--text-1);
  font-size: 16px;
  cursor: pointer;
}

/* ---------- 移动端抽屉 ---------- */
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.drawer-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--bg-sunken);
  color: var(--text-2);
  font-size: 14px;
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.drawer-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}

.drawer-menu {
  border-inline-end: none;
}

.drawer-menu :deep(.ant-menu-item) {
  height: 44px;
  line-height: 44px;
  margin-inline: 0;
  margin-bottom: 4px;
  border-radius: var(--radius-md);
  font-weight: 500;
}

.drawer-menu :deep(.ant-menu-item-selected) {
  background: var(--gradient-brand-soft);
  color: var(--brand-600);
}

.drawer-foot {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-5);
  padding-top: var(--space-5);
  border-top: 1px solid var(--border);
}

@media (max-width: 900px) {
  .header-nav,
  .user-chip {
    display: none;
  }

  .header-right :deep(.ant-btn) {
    display: none;
  }

  .nav-toggle {
    display: inline-flex;
  }
}
</style>
