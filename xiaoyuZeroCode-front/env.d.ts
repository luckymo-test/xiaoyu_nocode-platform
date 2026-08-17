/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API 基地址；开发默认走 vite proxy（/api），部署时用 .env 覆盖 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
