export interface MenuItem {
  key: string
  label: string
  path: string
  icon?: string
  role?: 'admin'
}

export const menuItems: MenuItem[] = [
  { key: 'home', label: '首页', path: '/' },
  { key: 'projects', label: '我的项目', path: '/projects' },
  { key: 'templates', label: '模板市场', path: '/templates' },
  { key: 'docs', label: '文档', path: '/docs' },
  { key: 'adminUser', label: '用户管理', path: '/admin/user', role: 'admin' },
]
