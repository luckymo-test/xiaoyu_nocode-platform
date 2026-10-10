import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

/**
 * antd 全局主题。
 *
 * 注意：当前 antd-vue 版本（4.2.x）的组件级 token 类型为空（`ComponentToken` 未实现），
 * 写 `components: { Button: {...} }` 会被 TypeScript 拒绝，所以这里只配置全局 token；
 * 组件级视觉统一在 `src/styles/antd.css` 里用 CSS 变量覆盖。
 */
export const antdTheme: ThemeConfig = {
  token: {
    /* 品牌色 */
    colorPrimary: '#4f6bff',
    colorInfo: '#4f6bff',
    colorLink: '#4f6bff',
    colorSuccess: '#22c55e',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',

    /* 文字与背景 */
    colorTextBase: '#1b1f2e',
    colorBgLayout: '#f5f6fb',
    colorBgContainer: '#ffffff',
    colorBgTextHover: '#f2f4fa',
    colorBgTextActive: '#eef0f7',

    /* 描边 */
    colorBorder: '#d6dae8',
    colorBorderSecondary: '#e6e8f2',

    /* 圆角与尺寸 */
    borderRadius: 10,
    borderRadiusXS: 6,
    borderRadiusSM: 8,
    borderRadiusLG: 16,
    controlHeight: 36,
    controlHeightSM: 28,
    controlHeightLG: 44,

    /* 字体 */
    fontSize: 14,
    fontFamily:
      "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif",

    /* 阴影 */
    boxShadow: '0 8px 24px rgba(20, 26, 48, 0.1)',
    boxShadowSecondary: '0 8px 24px rgba(20, 26, 48, 0.1)',
    boxShadowTertiary: '0 1px 2px rgba(20, 26, 48, 0.05), 0 2px 6px rgba(20, 26, 48, 0.06)',

    wireframe: false,
  },
}
