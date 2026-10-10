<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRightOutlined, EyeOutlined } from '@ant-design/icons-vue'

const props = defineProps<{
  app: API.AppVO
  /** 是否展示标签行（公开应用用） */
  showTags?: boolean
  /** 是否展示可见性标签 */
  showVisibility?: boolean
}>()

defineEmits<{
  (e: 'open', id?: number): void
}>()

const title = computed(() => props.app.appName || '未命名应用')
const cover = computed(() => props.app.compressPictures || props.app.cover || '')
const initial = computed(() => (props.app.appName || 'A').charAt(0).toUpperCase())
const description = computed(
  () => props.app.description || props.app.initPrompt || '暂无描述',
)
const tags = computed(() =>
  (props.app.tags || '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 3),
)
</script>

<template>
  <article class="app-card" tabindex="0" @click="$emit('open', app.id)" @keydown.enter="$emit('open', app.id)">
    <div class="app-card-cover">
      <img v-if="cover" :src="cover" :alt="title" loading="lazy" />
      <div v-else class="app-card-fallback">{{ initial }}</div>
      <span class="app-card-hint">
        <EyeOutlined />
        打开应用
      </span>
    </div>

    <div class="app-card-body">
      <h3 class="app-card-title">{{ title }}</h3>
      <p class="app-card-desc">{{ description }}</p>
      <div v-if="showTags || showVisibility" class="app-card-tags">
        <a-tag v-if="showVisibility && app.visibility" color="blue">
          {{ app.visibility === 'public' ? '公开' : '私有' }}
        </a-tag>
        <template v-if="showTags">
          <a-tag v-for="tag in tags" :key="tag">{{ tag }}</a-tag>
        </template>
        <span class="app-card-more">
          进入
          <ArrowRightOutlined />
        </span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.app-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--bg-surface);
  box-shadow: var(--shadow-1);
  cursor: pointer;
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.app-card:hover,
.app-card:focus-visible {
  transform: translateY(-3px);
  border-color: var(--brand-200);
  box-shadow: var(--shadow-2);
}

.app-card-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: var(--gradient-brand-soft);
}

.app-card-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--dur-slow) var(--ease);
}

.app-card:hover .app-card-cover img {
  transform: scale(1.04);
}

.app-card-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  background: var(--gradient-brand);
  color: var(--text-inverse);
  font-size: 34px;
  font-weight: 700;
}

.app-card-hint {
  position: absolute;
  right: var(--space-2);
  bottom: var(--space-2);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: rgba(14, 17, 32, 0.62);
  color: var(--text-inverse);
  font-size: var(--font-xs);
  opacity: 0;
  transform: translateY(4px);
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
  backdrop-filter: blur(6px);
}

.app-card:hover .app-card-hint {
  opacity: 1;
  transform: none;
}

.app-card-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-4);
}

.app-card-title {
  overflow: hidden;
  font-size: var(--font-lg);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-card-desc {
  display: -webkit-box;
  overflow: hidden;
  color: var(--text-2);
  font-size: var(--font-sm);
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  min-height: 42px;
}

.app-card-tags {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-wrap: wrap;
  margin-top: var(--space-2);
}

.app-card-tags :deep(.ant-tag) {
  margin-inline-end: 0;
}

.app-card-more {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  color: var(--brand-500);
  font-size: var(--font-sm);
  font-weight: 500;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.app-card:hover .app-card-more {
  opacity: 1;
}
</style>
