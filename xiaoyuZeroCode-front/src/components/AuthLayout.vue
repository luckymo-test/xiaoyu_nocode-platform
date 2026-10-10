<script setup lang="ts">
import { AppstoreOutlined, EditOutlined, ThunderboltFilled } from '@ant-design/icons-vue'

const points = [
  { icon: ThunderboltFilled, text: '一句话生成，秒级看到预览效果' },
  { icon: AppstoreOutlined, text: '支持单文件、多文件与 Vue3 工程' },
  { icon: EditOutlined, text: '可视化编辑，点中哪里就改哪里' },
]
</script>

<template>
  <div class="auth-layout">
    <section class="auth-brand">
      <div class="auth-brand-glow" aria-hidden="true" />
      <div class="auth-brand-inner">
        <img src="/logo.svg" alt="" class="auth-logo" />
        <h1 class="auth-name">小玉零代码</h1>
        <p class="auth-slogan">把想法说出来，剩下的交给小玉</p>
        <ul class="auth-points">
          <li v-for="point in points" :key="point.text">
            <span class="auth-point-icon"><component :is="point.icon" /></span>
            {{ point.text }}
          </li>
        </ul>
      </div>
    </section>

    <section class="auth-form">
      <div class="auth-form-inner">
        <slot />
      </div>
    </section>
  </div>
</template>

<style scoped>
.auth-layout {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  overflow: hidden;
  min-height: min(640px, calc(100vh - var(--header-h) - var(--main-pad) * 2 - var(--footer-h)));
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--bg-surface);
  box-shadow: var(--shadow-2);
}

/* ---------- 左：品牌区 ---------- */
.auth-brand {
  position: relative;
  display: flex;
  align-items: center;
  padding: var(--space-7);
  overflow: hidden;
  background: var(--gradient-brand);
  color: var(--text-inverse);
}

.auth-brand-glow {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(50% 40% at 20% 12%, rgba(255, 255, 255, 0.32) 0%, rgba(255, 255, 255, 0) 70%),
    radial-gradient(45% 40% at 88% 88%, rgba(34, 211, 238, 0.34) 0%, rgba(34, 211, 238, 0) 72%);
}

.auth-brand-inner {
  position: relative;
  z-index: 1;
  max-width: 360px;
}

.auth-logo {
  display: block;
  width: 52px;
  height: 52px;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 15px;
  box-shadow: 0 10px 26px rgba(14, 17, 32, 0.28);
}

.auth-name {
  margin-top: var(--space-4);
  color: var(--text-inverse);
  font-size: var(--font-3xl);
  font-weight: 700;
  letter-spacing: 1px;
}

.auth-slogan {
  margin-top: var(--space-2);
  color: rgba(255, 255, 255, 0.86);
  font-size: var(--font-lg);
}

.auth-points {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: var(--space-6) 0 0;
  padding: 0;
  list-style: none;
}

.auth-points li {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  color: rgba(255, 255, 255, 0.92);
  font-size: var(--font-base);
}

.auth-point-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.18);
  flex-shrink: 0;
}

/* ---------- 右：表单区 ---------- */
.auth-form {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-7) var(--space-6);
}

.auth-form-inner {
  width: 100%;
  max-width: 360px;
}

/* 表单内的通用排版（插槽内容由使用方写，样式在这里统一） */
.auth-form-inner :deep(.auth-title) {
  font-size: var(--font-2xl);
  font-weight: 700;
}

.auth-form-inner :deep(.auth-subtitle) {
  margin-top: var(--space-1);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.auth-form-inner :deep(.auth-form-body) {
  margin-top: var(--space-5);
}

.auth-form-inner :deep(.auth-footer) {
  margin-top: var(--space-2);
  color: var(--text-2);
  font-size: var(--font-sm);
  text-align: center;
}

.auth-form-inner :deep(.ant-input-affix-wrapper-lg),
.auth-form-inner :deep(.ant-input-lg) {
  border-radius: var(--radius-md);
}

@media (max-width: 900px) {
  .auth-layout {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .auth-brand {
    padding: var(--space-5);
  }

  .auth-logo {
    width: 40px;
    height: 40px;
    border-radius: 12px;
  }

  .auth-name {
    margin-top: var(--space-3);
    font-size: var(--font-2xl);
  }

  .auth-slogan {
    font-size: var(--font-base);
  }

  .auth-points {
    display: none;
  }

  .auth-form {
    padding: var(--space-5);
  }
}
</style>
