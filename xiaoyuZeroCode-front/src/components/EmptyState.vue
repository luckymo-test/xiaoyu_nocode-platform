<script setup lang="ts">
import { InboxOutlined } from '@ant-design/icons-vue'

defineProps<{
  title?: string
  description?: string
}>()
</script>

<template>
  <div class="empty-state">
    <span class="empty-icon">
      <slot name="icon">
        <InboxOutlined />
      </slot>
    </span>
    <h3 class="empty-title">{{ title || '暂无内容' }}</h3>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div class="empty-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-7) var(--space-5);
  text-align: center;
}

.empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-lg);
  background: var(--gradient-brand-soft);
  color: var(--brand-500);
  font-size: 24px;
}

.empty-title {
  margin-top: var(--space-2);
  font-size: var(--font-lg);
  font-weight: 600;
}

.empty-desc {
  max-width: 420px;
  color: var(--text-2);
  font-size: var(--font-sm);
}

.empty-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.empty-actions:empty {
  display: none;
}
</style>
