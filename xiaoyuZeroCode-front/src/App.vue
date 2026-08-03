<script setup lang="ts">
import { onMounted } from 'vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

onMounted(() => {
  userStore.fetchLoginUser()
})
</script>

<template>
  <a-app>
    <BasicLayout />
  </a-app>
</template>
