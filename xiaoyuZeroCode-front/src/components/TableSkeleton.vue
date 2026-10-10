<script setup lang="ts">
/**
 * 表格首屏骨架屏。
 * 用法：仅在"首次加载且还没有数据"时替换表格，避免刷新数据时整块闪烁。
 *   <TableSkeleton v-if="loading && list.length === 0" :rows="6" :cols="5" />
 *   <a-table v-else ... />
 */
withDefaults(
  defineProps<{
    rows?: number
    cols?: number
  }>(),
  { rows: 6, cols: 5 },
)
</script>

<template>
  <div class="table-skeleton" :style="`--cols: ${cols}`" aria-busy="true" aria-label="加载中">
    <div v-for="r in rows" :key="r" class="ts-row">
      <span v-for="c in cols" :key="c" class="ts-cell" />
    </div>
  </div>
</template>

<style scoped>
.table-skeleton {
  padding: var(--space-2) 0;
}

.ts-row {
  display: grid;
  grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
  align-items: center;
  gap: var(--space-4);
  padding: 15px 0;
  border-bottom: 1px solid var(--border);
}

.ts-row:last-child {
  border-bottom: none;
}

.ts-cell {
  height: 12px;
  border-radius: var(--radius-xs);
  background: linear-gradient(
    90deg,
    var(--bg-sunken) 25%,
    var(--bg-hover) 37%,
    var(--bg-sunken) 63%
  );
  background-size: 400% 100%;
  animation: ts-shimmer 1.4s ease infinite;
}

/* 第一列（通常是名称）宽一点，更像真实表格 */
.ts-row .ts-cell:first-child {
  width: 72%;
}

.ts-row .ts-cell:nth-child(2n) {
  width: 58%;
}

@keyframes ts-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
</style>
