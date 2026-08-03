<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { MenuItem } from '@/config/menu'

const props = defineProps<{
  menuItems: MenuItem[]
}>()

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const selectedKeys = ref<string[]>([route.name as string])

const visibleMenuItems = computed(() =>
  props.menuItems.filter((item) => {
    if (item.role === 'admin') {
      return userStore.loginUser?.userRole === 'admin'
    }
    return true
  }),
)

const handleMenuClick = (item: MenuItem) => {
  router.push(item.path)
}

const handleLogout = async () => {
  await userStore.logout()
  router.push('/')
}
</script>

<template>
  <a-layout-header class="global-header">
    <div class="header-left">
      <img src="/logo.svg" alt="logo" class="logo-img" />
      <span class="header-title">小玉零代码</span>
    </div>

    <a-menu
      class="header-menu"
      v-model:selectedKeys="selectedKeys"
      theme="dark"
      mode="horizontal"
    >
      <a-menu-item
        v-for="item in visibleMenuItems"
        :key="item.key"
        @click="handleMenuClick(item)"
      >
        {{ item.label }}
      </a-menu-item>
    </a-menu>

    <div class="header-right">
      <template v-if="userStore.loginUser">
        <a-dropdown trigger="hover">
          <a-space class="user-info">
            <a-avatar :src="userStore.loginUser.userAvatar" size="small">
              {{ userStore.loginUser.userName?.charAt(0) || 'U' }}
            </a-avatar>
            <span class="user-name">{{ userStore.loginUser.userName }}</span>
          </a-space>
          <template #overlay>
            <a-menu>
              <a-menu-item key="profile">
                <router-link to="/user/center">个人中心</router-link>
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" @click="handleLogout">
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </template>
      <template v-else>
        <a-button type="primary" size="small" @click="router.push('/user/login')">
          登录
        </a-button>
      </template>
    </div>
  </a-layout-header>
</template>

<style scoped>
.global-header {
  display: flex;
  align-items: center;
  padding: 0 24px;
  background: #001529;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  margin-right: 24px;
  flex-shrink: 0;
}

.logo-img {
  width: 32px;
  height: 32px;
  margin-right: 12px;
}

.header-title {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  white-space: nowrap;
}

.header-menu {
  flex: 1;
  min-width: 0;
}

.header-right {
  margin-left: auto;
  flex-shrink: 0;
}

.user-info {
  cursor: pointer;
}

.user-name {
  color: rgba(255, 255, 255, 0.85);
  margin-left: 4px;
}
</style>
