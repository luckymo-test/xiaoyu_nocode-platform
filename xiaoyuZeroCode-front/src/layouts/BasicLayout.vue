<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { App as AntdApp } from 'ant-design-vue'
import GlobalHeader from './GlobalHeader.vue'
import GlobalFooter from './GlobalFooter.vue'
import { menuItems } from '@/config/menu'
import { registerMessage } from '@/utils/feedback'

const route = useRoute()

/** 生成页是整屏工作区，不显示页脚，避免多出一条滚动 */
const showFooter = computed(() => !route.path.startsWith('/app/gen/'))

// 注册跟随主题的 message 实例，供非组件模块（request / router / stores）复用
const { message } = AntdApp.useApp()
registerMessage(message)
</script>

<template>
  <div class="app-shell">
    <div class="app-bg-glow" aria-hidden="true" />
    <GlobalHeader :menu-items="menuItems" />
    <main class="app-main">
      <router-view />
    </main>
    <GlobalFooter v-if="showFooter" />
  </div>
</template>

<style scoped>
.app-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--bg-app);
}

.app-bg-glow {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(58% 46% at 10% -8%, rgba(91, 124, 255, 0.22) 0%, rgba(91, 124, 255, 0) 70%),
    radial-gradient(46% 40% at 94% 2%, rgba(168, 85, 247, 0.16) 0%, rgba(168, 85, 247, 0) 72%);
}

.app-main {
  position: relative;
  z-index: 1;
  flex: 1;
  width: 100%;
  max-width: var(--page-max);
  margin: 0 auto;
  padding: var(--main-pad);
}
</style>
