<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

interface Props {
  content: string
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
  <div class="markdown-body" v-html="rendered" />
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
</style>
