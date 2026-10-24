import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const generatedRoot = path.join(root, 'src', 'generated', 'api')
const config = fs.readFileSync(path.join(root, 'openapi2ts.config.ts'), 'utf8')

if (!config.includes("serversPath: './src/generated'")) {
  throw new Error('OpenAPI 生成目录必须与手写 src/api 分离')
}
if (!config.includes("namespace: 'GeneratedAPI'")) {
  throw new Error('OpenAPI 生成类型必须使用 GeneratedAPI 命名空间')
}
if (!fs.existsSync(generatedRoot) || !fs.statSync(generatedRoot).isDirectory()) {
  throw new Error('未找到 src/generated/api，OpenAPI Client 生成失败')
}

const generatedFiles = fs.readdirSync(generatedRoot, { recursive: true })
  .filter((file) => typeof file === 'string' && file.endsWith('.ts'))
if (generatedFiles.length === 0) {
  throw new Error('src/generated/api 中没有生成任何 TypeScript Client')
}

const protectedHandwrittenFiles = [
  'src/api/aiStream.ts',
  'src/api/manualEdit.ts',
  'src/api/renderFix.ts',
  'src/api/runtimeProfile.ts',
  'src/api/appRevision.ts',
]
for (const relative of protectedHandwrittenFiles) {
  if (!fs.existsSync(path.join(root, relative))) {
    throw new Error(`OpenAPI 生成误删手写 API：${relative}`)
  }
}

console.log(
  `OpenAPI client verified: ${generatedFiles.length} generated files, `
  + `${protectedHandwrittenFiles.length} handwritten API files preserved`,
)
