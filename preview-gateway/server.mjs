import http from 'node:http'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createReadStream } from 'node:fs'

const port = Number(process.env.PREVIEW_GATEWAY_PORT || 8088)
const workspaceRoot = path.resolve(process.env.PREVIEW_WORKSPACE_ROOT || './tmp')
const deployRoot = path.resolve(process.env.PREVIEW_DEPLOY_ROOT || './deploy')
const secret = process.env.PREVIEW_SIGNING_SECRET || ''
const apiOrigin = process.env.PREVIEW_API_ORIGIN || 'http://localhost:8124'

if (secret.length < 16) {
  throw new Error('PREVIEW_SIGNING_SECRET must contain at least 16 characters')
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
}

function verify(token) {
  const index = token.lastIndexOf('.')
  if (index <= 0) throw new Error('invalid token')
  const payload = token.slice(0, index)
  const signature = token.slice(index + 1)
  const expected = crypto.createHmac('sha256', secret)
    .update(payload).digest('base64url')
  if (signature.length !== expected.length
      || !crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) {
    throw new Error('invalid signature')
  }
  const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
  if (Number(data.exp) <= Math.floor(Date.now() / 1000)) throw new Error('expired')
  return data
}

async function proxyRuntime(req, res) {
  const target = new URL(`/api${req.url}`, apiOrigin)
  const upstream = await fetch(target, {
    method: req.method,
    headers: { ...req.headers, host: new URL(apiOrigin).host },
    body: ['GET', 'HEAD'].includes(req.method) ? undefined : req,
    duplex: ['GET', 'HEAD'].includes(req.method) ? undefined : 'half',
  })
  res.writeHead(upstream.status, Object.fromEntries(upstream.headers))
  if (upstream.body) {
    for await (const chunk of upstream.body) res.write(chunk)
  }
  res.end()
}

async function serveFile(res, root, relative) {
  let file = path.resolve(root, relative)
  if (!file.startsWith(root + path.sep) && file !== root) {
    res.writeHead(403)
    res.end('forbidden')
    return
  }
  let stat = await fs.stat(file).catch(() => null)
  if ((!stat || stat.isDirectory()) && !path.extname(relative)) {
    file = path.join(root, 'index.html')
    stat = await fs.stat(file).catch(() => null)
  }
  if (!stat?.isFile()) {
    res.writeHead(404)
    res.end('not found')
    return
  }
  res.writeHead(200, {
    'Content-Type': mime[path.extname(file).toLowerCase()] || 'application/octet-stream',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy':
      "default-src 'self' data: blob: https:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; connect-src 'self' https:;",
  })
  createReadStream(file).pipe(res)
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.url?.startsWith('/runtime/')
        || req.url?.startsWith('/v1/security/')
        || req.url?.startsWith('/v1/apps/')) {
      await proxyRuntime(req, res)
      return
    }
    const deployed = req.url?.match(/^\/apps\/([A-Za-z0-9]+)(?:\/(.*))?$/)
    if (deployed) {
      const key = deployed[1]
      const relative = decodeURIComponent(deployed[2] || 'index.html') || 'index.html'
      await serveFile(res, path.resolve(deployRoot, key), relative)
      return
    }
    const match = req.url?.match(/^\/preview\/([^/]+)(?:\/(.*))?$/)
    if (!match) {
      res.writeHead(404)
      res.end('not found')
      return
    }
    const data = verify(match[1])
    const appId = String(data.appId)
    const type = String(data.type)
    if (!/^\d+$/.test(appId) || !['html', 'multi_file', 'vue'].includes(type)) {
      throw new Error('invalid payload')
    }
    const projectRoot = path.resolve(
      workspaceRoot,
      type === 'vue' ? `vue_${appId}/dist` : `${type}_${appId}`,
    )
    let relative = decodeURIComponent(match[2] || 'index.html')
    if (relative === '') relative = 'index.html'
    await serveFile(res, projectRoot, relative)
  } catch (error) {
    res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end(error instanceof Error ? error.message : 'unauthorized')
  }
})

server.listen(port, '0.0.0.0', () => {
  console.log(`preview gateway listening on ${port}`)
})
