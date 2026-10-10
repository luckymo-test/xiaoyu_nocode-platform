<script setup lang="ts">
import { onMounted } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useUserStore } from '@/stores/user'
import { antdTheme } from '@/config/theme'

const userStore = useUserStore()

onMounted(() => {
  userStore.fetchLoginUser()
})
</script>

<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <a-app>
      <BasicLayout />
    </a-app>
  </a-config-provider>
</template>
