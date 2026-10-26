<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import CssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker'
import HtmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker'
import TsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker'

const props = defineProps<{
  value: string
  language?: string
  readOnly?: boolean
}>()

const emit = defineEmits<{
  'update:value': [value: string]
}>()

const host = ref<HTMLElement | null>(null)
let editor: monaco.editor.IStandaloneCodeEditor | null = null

;(self as unknown as {
  MonacoEnvironment: {
    getWorker: (_moduleId: string, label: string) => Worker
  }
}).MonacoEnvironment = {
  getWorker(_moduleId: string, label: string) {
    if (label === 'json') return new JsonWorker()
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker()
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker()
    if (label === 'typescript' || label === 'javascript') return new TsWorker()
    return new EditorWorker()
  },
}

onMounted(() => {
  if (!host.value) return
  editor = monaco.editor.create(host.value, {
    value: props.value,
    language: props.language || 'plaintext',
    readOnly: props.readOnly ?? false,
    theme: 'vs-dark',
    automaticLayout: true,
    minimap: { enabled: false },
    fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace',
    fontSize: 13,
    lineHeight: 22,
    scrollBeyondLastLine: false,
    wordWrap: 'off',
    padding: { top: 12, bottom: 12 },
  })
  editor.onDidChangeModelContent(() => {
    if (!props.readOnly) emit('update:value', editor?.getValue() ?? '')
  })
})

watch(
  () => props.value,
  (value) => {
    if (editor && editor.getValue() !== value) editor.setValue(value)
  },
)

watch(
  () => props.language,
  (language) => {
    const model = editor?.getModel()
    if (model && language) monaco.editor.setModelLanguage(model, language)
  },
)

onBeforeUnmount(() => {
  editor?.dispose()
  editor = null
})
</script>

<template>
  <div ref="host" class="code-editor"></div>
</template>

<style scoped>
.code-editor {
  width: 100%;
  height: 100%;
  min-height: 440px;
}
</style>
