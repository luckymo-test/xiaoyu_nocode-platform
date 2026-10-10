import type { Component } from 'vue'
import {
  AppstoreOutlined,
  CommentOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  HomeOutlined,
  RocketOutlined,
  TeamOutlined,
} from '@ant-design/icons-vue'

export interface MenuItem {
  key: string
  label: string
  path: string
  icon?: Component
  role?: 'admin'
}

export const menuItems: MenuItem[] = [
  { key: 'home', label: '首页', path: '/', icon: HomeOutlined },
  { key: 'projects', label: '我的项目', path: '/projects', icon: FolderOpenOutlined },
  { key: 'templates', label: '模板市场', path: '/templates', icon: AppstoreOutlined },
  { key: 'docs', label: '文档', path: '/docs', icon: FileTextOutlined },
  { key: 'adminUser', label: '用户管理', path: '/admin/user', icon: TeamOutlined, role: 'admin' },
  { key: 'adminApp', label: '应用管理', path: '/admin/app', icon: RocketOutlined, role: 'admin' },
  { key: 'adminChat', label: '对话管理', path: '/admin/chat', icon: CommentOutlined, role: 'admin' },
]
