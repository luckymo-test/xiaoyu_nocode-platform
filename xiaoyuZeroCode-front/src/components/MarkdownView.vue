<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

interface Props {
  content: string
  /** 深色模式：用于代码预览等深色工作区 */
  dark?: boolean
}

const props = defineProps<Props>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        // 高亮失败时按纯文本转义输出
      }
    }
    // 未识别语言时转义后输出
    return md.utils.escapeHtml(code)
  },
})

const rendered = computed(() => md.render(props.content))
</script>

<template>
  <div class="markdown-body" :class="{ 'is-dark': dark }" v-html="rendered" />
</template>

<style scoped>
.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 0 0 8px;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 12px 0 8px;
  font-weight: 600;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
  margin: 0 0 8px;
}

.markdown-body :deep(pre) {
  background: #f6f8fa;
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
  margin: 8px 0;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
}

.markdown-body :deep(code:not(pre code)) {
  background: rgba(175, 184, 193, 0.2);
  border-radius: 3px;
  padding: 2px 4px;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
}

.markdown-body :deep(a) {
  color: #1677ff;
}

.markdown-body :deep(blockquote) {
  border-left: 4px solid #d0d7de;
  padding-left: 12px;
  color: #57606a;
  margin: 8px 0;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
}

.markdown-body :deep(table th),
.markdown-body :deep(table td) {
  border: 1px solid #d0d7de;
  padding: 6px 12px;
}

/* ---------- 深色模式（代码预览 / 深色工作区） ---------- */
.markdown-body.is-dark {
  color: var(--dark-text-1);
}

.markdown-body.is-dark :deep(p),
.markdown-body.is-dark :deep(li) {
  color: var(--dark-text-1);
}

.markdown-body.is-dark :deep(h1),
.markdown-body.is-dark :deep(h2),
.markdown-body.is-dark :deep(h3),
.markdown-body.is-dark :deep(h4) {
  color: #ffffff;
}

.markdown-body.is-dark :deep(pre) {
  background: var(--dark-bg-2);
  border: 1px solid var(--dark-border);
}

.markdown-body.is-dark :deep(code:not(pre code)) {
  background: rgba(255, 255, 255, 0.1);
  color: var(--dark-text-1);
}

.markdown-body.is-dark :deep(a) {
  color: #8fb0ff;
}

.markdown-body.is-dark :deep(blockquote) {
  border-left-color: rgba(255, 255, 255, 0.2);
  color: var(--dark-text-2);
}

.markdown-body.is-dark :deep(table th),
.markdown-body.is-dark :deep(table td) {
  border-color: rgba(255, 255, 255, 0.16);
  color: var(--dark-text-1);
}

/* hljs 默认引入的是浅色（github）主题，这里覆盖主要 token 配色 */
.markdown-body.is-dark :deep(.hljs) {
  background: transparent;
  color: var(--dark-text-1);
}

.markdown-body.is-dark :deep(.hljs-comment),
.markdown-body.is-dark :deep(.hljs-quote) {
  color: #7d8598;
  font-style: italic;
}

.markdown-body.is-dark :deep(.hljs-keyword),
.markdown-body.is-dark :deep(.hljs-selector-tag),
.markdown-body.is-dark :deep(.hljs-literal),
.markdown-body.is-dark :deep(.hljs-doctag) {
  color: #c792ea;
}

.markdown-body.is-dark :deep(.hljs-string),
.markdown-body.is-dark :deep(.hljs-regexp),
.markdown-body.is-dark :deep(.hljs-addition) {
  color: #a5e075;
}

.markdown-body.is-dark :deep(.hljs-number),
.markdown-body.is-dark :deep(.hljs-built_in),
.markdown-body.is-dark :deep(.hljs-symbol) {
  color: #ffb86c;
}

.markdown-body.is-dark :deep(.hljs-title),
.markdown-body.is-dark :deep(.hljs-section),
.markdown-body.is-dark :deep(.hljs-name) {
  color: #82aaff;
}

.markdown-body.is-dark :deep(.hljs-attr),
.markdown-body.is-dark :deep(.hljs-attribute),
.markdown-body.is-dark :deep(.hljs-variable),
.markdown-body.is-dark :deep(.hljs-template-variable) {
  color: #ffcb6b;
}

.markdown-body.is-dark :deep(.hljs-tag) {
  color: #8b98b8;
}

.markdown-body.is-dark :deep(.hljs-deletion) {
  color: #ff6b81;
}
</style>
