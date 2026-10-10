<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
const year = new Date().getFullYear()
</script>

<template>
  <footer class="global-footer">
    <div class="footer-inner">
      <div class="footer-brand">
        <img src="/logo.svg" alt="" class="footer-logo" />
        <span class="footer-name">小玉零代码</span>
        <span class="footer-divider" />
        <span class="footer-slogan">输入你的需求，AI 为你生成网站应用</span>
      </div>
      <nav class="footer-links">
        <a class="footer-link" @click="router.push('/projects')">我的项目</a>
        <a class="footer-link" @click="router.push('/docs')">使用文档</a>
        <span class="footer-version">v0.0.1</span>
      </nav>
    </div>
    <div class="footer-copy">© {{ year }} 小玉零代码平台 · 仅用于学习与演示</div>
  </footer>
</template>

<style scoped>
.global-footer {
  position: relative;
  z-index: 1;
  margin-top: auto;
  padding: var(--space-5) var(--main-pad) var(--space-6);
  border-top: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(10px);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.footer-inner {
  max-width: var(--page-max);
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.footer-brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.footer-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--radius-xs);
}

.footer-name {
  color: var(--text-1);
  font-weight: 600;
}

.footer-divider {
  width: 1px;
  height: 12px;
  background: var(--border-strong);
}

.footer-slogan {
  color: var(--text-3);
}

.footer-links {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.footer-link {
  color: var(--text-2);
  cursor: pointer;
  transition: color var(--dur) var(--ease);
}

.footer-link:hover {
  color: var(--brand-500);
}

.footer-version {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--bg-sunken);
  color: var(--text-3);
  font-size: var(--font-xs);
}

.footer-copy {
  max-width: var(--page-max);
  margin: var(--space-3) auto 0;
  color: var(--text-3);
  font-size: var(--font-xs);
}

@media (max-width: 768px) {
  .footer-brand {
    flex-wrap: wrap;
  }

  .footer-divider,
  .footer-slogan {
    display: none;
  }
}
</style>
