import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5199,
    proxy: {
      // 开发环境把 /api 代理到后端，前后端同源，避免跨域
      '/api': {
        target: 'http://localhost:8124',
        changeOrigin: true,
      },
    },
  },
})
