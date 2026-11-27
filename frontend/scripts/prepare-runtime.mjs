#!/usr/bin/env node
/**
 * 准备「离线联网搜索运行时」：frontend/runtime/mcp
 *
 * 为什么不用 npx：
 *   npx 每次启动都要解析包、比对 registry 上的 latest，本机实测固定多花 5 秒；
 *   而且要求目标机器自己装了 Node.js。把包装进应用目录、启动时直接
 *   `node build/index.js`，耗时降到 0.3 秒，也不再依赖用户环境。
 *
 * 为什么不带 node.exe：
 *   Electron 自带 Node（35.7.5 → Node 22.16.0），加 ELECTRON_RUN_AS_NODE=1
 *   就是一个纯 Node，可直接跑这个入口（唯一原生依赖 koffi 是 N-API 模块，
 *   跨 ABI 可用，已实测 `search` 成功）。省下 83MB。
 *   只有 Electron 未安装（没跑过 npm install）时才回退复制独立 node。
 *
 * 幂等：入口已存在就跳过安装，正常第二次执行只要几十毫秒。
 * 用法：npm run runtime（npm run electron / npm run dist 都会自动先跑一次）
 */
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// 与 application.yaml 注释里的版本保持一致；升级时两边一起改
const OPEN_WEBSEARCH_VERSION = '2.2.0'

const scriptsDir = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(scriptsDir, '..')
const runtime = path.join(frontend, 'runtime')
const nodeDir = path.join(runtime, 'node')
const mcpDir = path.join(runtime, 'mcp')
const nodeExe = path.join(nodeDir, process.platform === 'win32' ? 'node.exe' : 'node')
const versionFile = path.join(nodeDir, 'VERSION')
const entry = path.join(mcpDir, 'node_modules', 'open-websearch', 'build', 'index.js')

const log = (m) => console.log(`[runtime] ${m}`)
const fail = (m) => {
  console.error(`[runtime] ${m}`)
  process.exit(1)
}

// ---- 1. Node 运行时：优先借 Electron 自带的那份 ----
const electronExe = path.join(
  frontend, 'node_modules', 'electron', 'dist',
  process.platform === 'win32' ? 'electron.exe' : 'Electron'
)
const useElectron = process.platform === 'win32' && fs.existsSync(electronExe)

if (useElectron) {
  // 历史产物（旧版脚本复制过 83MB 的 node.exe）在这里一并清掉。
  // 删除失败不算错（文件被占用时会失败），下次启动仍会优先选 Electron。
  if (fs.existsSync(nodeDir)) {
    try {
      fs.rmSync(nodeDir, { recursive: true, force: true })
      log('已删除多余的独立 Node，改用 Electron 内置 Node')
    } catch (e) {
      log(`独立 Node 目录清理失败（不影响使用）：${e.message}`)
    }
  } else {
    log('Node 运行时：使用 Electron 内置 Node（ELECTRON_RUN_AS_NODE）')
  }
} else {
  // 回退：复制「正在执行本脚本的 node 自己」，不写死安装路径、不联网下载
  fs.mkdirSync(nodeDir, { recursive: true })
  let needNode = !fs.existsSync(nodeExe)
  if (!needNode) {
    try {
      needNode = fs.readFileSync(versionFile, 'utf8').trim() !== process.version
    } catch {
      needNode = true
    }
  }
  if (needNode) {
    const mb = (fs.statSync(process.execPath).size / 1048576).toFixed(0)
    log(`未找到 Electron，回退复制 Node ${process.version}（${mb} MB）`)
    fs.copyFileSync(process.execPath, nodeExe)
    fs.writeFileSync(versionFile, process.version, 'utf8')
  } else {
    log(`Node ${process.version} 已就绪（独立副本）`)
  }
}

// ---- 2. open-websearch 及其依赖 ----
if (fs.existsSync(entry)) {
  log(`open-websearch ${OPEN_WEBSEARCH_VERSION} 已就绪`)
} else {
  fs.mkdirSync(mcpDir, { recursive: true })
  const pkgFile = path.join(mcpDir, 'package.json')
  if (!fs.existsSync(pkgFile)) {
    fs.writeFileSync(
      pkgFile,
      JSON.stringify(
        {
          name: 'insightlens-runtime',
          private: true,
          description: '离线联网搜索运行时，由 scripts/prepare-runtime.mjs 生成，勿手改',
          dependencies: { 'open-websearch': OPEN_WEBSEARCH_VERSION }
        },
        null,
        2
      ) + '\n',
      'utf8'
    )
  }
  log(`安装 open-websearch@${OPEN_WEBSEARCH_VERSION}（首次约 30-60 秒）`)
  const r = spawnSync('npm', ['install', '--omit=dev', '--no-audit', '--no-fund', '--loglevel=error'], {
    cwd: mcpDir,
    stdio: 'inherit',
    shell: true
  })
  if (r.status !== 0) {
    fail('npm install 失败：检查网络与 npm 源（npm config get registry），或手动在该目录执行 npm install')
  }
}

if (!fs.existsSync(entry)) fail(`入口不存在：${entry}`)

// ---- 3. 剪掉用不到的平台二进制 ----
// koffi 的 index.js 只按 `${process.platform}_${process.arch}` 拼路径加载，
// 不会遍历目录，所以其它平台的 .node 纯属死重量（18 份共 25MB，只留 1.5MB）。
const koffiBuild = path.join(mcpDir, 'node_modules', 'koffi', 'build', 'koffi')
const triplet = `${process.platform}_${process.arch}`
if (fs.existsSync(koffiBuild)) {
  let removed = 0
  for (const d of fs.readdirSync(koffiBuild, { withFileTypes: true })) {
    if (!d.isDirectory() || d.name === triplet) continue
    try {
      fs.rmSync(path.join(koffiBuild, d.name), { recursive: true, force: true })
      removed++
    } catch { /* 占用中：留着不影响功能，只是多占几 MB */ }
  }
  if (removed > 0) log(`清理 koffi 其它平台二进制 ${removed} 份（保留 ${triplet}）`)
}
// 文档与 C 源码不参与运行
for (const junk of ['doc', 'src', 'vendor']) {
  const p = path.join(mcpDir, 'node_modules', 'koffi', junk)
  try { if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true }) } catch { /* 同上 */ }
}

function dirSize(p) {
  let total = 0
  for (const d of fs.readdirSync(p, { withFileTypes: true })) {
    const f = path.join(p, d.name)
    total += d.isDirectory() ? dirSize(f) : fs.statSync(f).size
  }
  return total
}

log(`完成：共 ${(dirSize(runtime) / 1048576).toFixed(0)} MB，Node 来源 ${useElectron ? 'Electron 内置' : '独立副本'}`)
log(`入口：${entry}`)
