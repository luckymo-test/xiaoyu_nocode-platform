<script setup lang="ts">
import GlobalHeader from './GlobalHeader.vue'
import GlobalFooter from './GlobalFooter.vue'
import { menuItems } from '@/config/menu'
</script>

<template>
  <a-layout class="basic-layout">
    <GlobalHeader :menuItems="menuItems" />
    <a-layout-content class="main-content">
      <router-view />
    </a-layout-content>
    <GlobalFooter />
  </a-layout>
</template>

<style scoped>
.basic-layout {
  min-height: 100vh;
}

.main-content {
  margin-top: 64px;
  margin-bottom: 50px;
  padding: 24px;
  background: #f5f5f5;
  min-height: calc(100vh - 64px - 50px);
}

@media (max-width: 768px) {
  .main-content {
    padding: 12px;
  }
}
</style>
