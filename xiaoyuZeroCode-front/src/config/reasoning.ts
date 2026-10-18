export type ReasoningLevel = 'fast' | 'eco' | 'standard' | 'max'

export const REASONING_LEVELS: Array<{ value: ReasoningLevel; label: string }> = [
  { value: 'fast', label: '极速' },
  { value: 'eco', label: '省算力' },
  { value: 'standard', label: '标准' },
  { value: 'max', label: '极限' },
]

export const REASONING_MARKS = Object.fromEntries(
  REASONING_LEVELS.map((item, index) => [index, item.label]),
)

export const DEFAULT_REASONING_INDEX = REASONING_LEVELS.findIndex(
  (item) => item.value === 'standard',
)
export const NEW_APP_REASONING_STORAGE_KEY = 'xy-reasoning-level:new'

export function parseReasoningIndex(value?: string | null): number {
  const index = REASONING_LEVELS.findIndex((item) => item.value === value)
  return index >= 0 ? index : DEFAULT_REASONING_INDEX
}

export function reasoningStorageKey(appId: string): string {
  return `xy-reasoning-level:${appId}`
}
