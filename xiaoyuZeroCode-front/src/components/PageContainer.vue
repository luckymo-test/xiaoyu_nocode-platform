<script setup lang="ts">
/**
 * 页面外壳：统一页面最大宽度、内边距、页面头（标题 + 描述 + 右侧操作槽）。
 * 用法：
 *   <PageContainer title="我的项目" description="管理你创建的应用">
 *     <template #extra><a-button type="primary">新建应用</a-button></template>
 *     ...页面内容
 *   </PageContainer>
 */
defineProps<{
  title?: string
  description?: string
  /** 窄版容器（表单类页面），默认宽版 1200px */
  narrow?: boolean
}>()
</script>

<template>
  <div class="page-container" :class="{ 'is-narrow': narrow }">
    <header v-if="title || description || $slots.extra" class="page-header">
      <div class="page-header-main">
        <h1 v-if="title" class="page-title">{{ title }}</h1>
        <p v-if="description" class="page-desc">{{ description }}</p>
        <slot name="header" />
      </div>
      <div v-if="$slots.extra" class="page-header-extra">
        <slot name="extra" />
      </div>
    </header>
    <slot />
  </div>
</template>

<style scoped>
.page-container {
  width: 100%;
  margin: 0 auto;
}

.page-container.is-narrow {
  max-width: 720px;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-5);
}

.page-header-main {
  min-width: 0;
}

.page-title {
  font-size: var(--font-2xl);
  font-weight: 700;
  letter-spacing: 0.2px;
}

.page-desc {
  margin-top: var(--space-1);
  color: var(--text-2);
  font-size: var(--font-base);
}

.page-header-extra {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
</style>
