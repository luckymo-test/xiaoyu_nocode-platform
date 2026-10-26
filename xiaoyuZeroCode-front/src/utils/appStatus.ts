// 应用状态文案映射（与后端枚举一致）

export interface StatusMeta {
  text: string
  color: string
}

// 生成状态：not_generated / generating / done / stopped / failed
export const generateStatusMap: Record<string, StatusMeta> = {
  not_generated: { text: '未生成', color: 'default' },
  generating: { text: '生成中', color: 'processing' },
  done: { text: '已完成', color: 'success' },
  paused: { text: '已暂停', color: 'warning' },
  stopped: { text: '已停止', color: 'warning' },
  failed: { text: '失败', color: 'error' },
}

// 精选状态：0未申请 / 1待审核 / 2已推荐 / 3已拒绝
export const featuredStatusMap: Record<number, StatusMeta> = {
  0: { text: '未申请', color: 'default' },
  1: { text: '待审核', color: 'processing' },
  2: { text: '已推荐', color: 'success' },
  3: { text: '已拒绝', color: 'error' },
}

// 可见范围：public / private
export const visibilityMap: Record<string, { text: string; color: string }> = {
  public: { text: '公开', color: 'blue' },
  private: { text: '私有', color: 'default' },
}

export function generateStatusText(status?: string) {
  return generateStatusMap[status ?? ''] ?? { text: status ?? '未知', color: 'default' }
}

export function featuredStatusText(status?: number) {
  return featuredStatusMap[status ?? 0] ?? { text: '未知', color: 'default' }
}

export function visibilityText(visibility?: string) {
  return visibilityMap[visibility ?? ''] ?? { text: visibility ?? '未知', color: 'default' }
}
