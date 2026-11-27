#!/usr/bin/env node
/**
 * 生成内置 Java 运行时：frontend/runtime/jre
 *
 * 为什么需要：
 *   安装包此前用 spawn('java') 启动后端，要求用户自备 JDK 17 并在 PATH 里。
 *   对下载安装的人这是硬门槛——没装 JDK 就只会看到「后端启动失败」。
 *   用 jlink 裁出只含必要模块的 JRE（实测 45MB，完整 JRE 是 180MB），
 *   随安装包分发，启动完全不依赖用户环境。
 *
 * 为什么不入 git：
 *   45MB 二进制，每次改这个脚本都会多存一份 blob。改为打包时幂等生成，
 *   仓库只保留脚本。换机器打包的前提是本机有 JDK 17–24（jlink 从这里来）。
 *
 * 模块集不是猜的：下面这套已实测通过三项关键验证——
 *   ① Spring Boot 全栈启动（12.4s，零 ClassNotFound）
 *   ② DuckDB JDBC 加载原生库并执行 select（v2.0.0）
 *   ③ java.net.http 走 HTTPS 完成 TLS 握手（jdk.crypto.ec 不可省）
 *   改模块集前请先用同样的方式回归，缺一个模块的错误往往只在运行期才炸。
 *
 * 幂等：已生成且主版本匹配（17–24）就直接跳过，第二次只要几百毫秒。
 * 用法：npm run jre（npm run dist 会自动先跑）
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

/** 运行期真正用到的模块，按字母序排，便于与 jlink 输出比对 */
const MODULES = [
  'java.base',            // 核心，含 sun.security.ssl
  'java.compiler',        // Spring 的 SpEL / 部分 ASM 路径会引用
  'java.desktop',         // AWT 相关类，日志与字体处理偶有引用
  'java.instrument',      // 部分 agent / AOP 场景
  'java.management',      // JMX 类虽被关闭，Spring 仍会加载接口
  'java.naming',          // JNDI
  'java.net.http',        // Spring AI 的 HttpClient 路径
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.security.sasl',
  'java.sql',             // DuckDB JDBC
  'java.sql.rowset',
  'java.transaction.xa',
  'java.xml',
  'jdk.crypto.cryptoki',
  'jdk.crypto.ec',        // HTTPS 的 ECDHE 密钥交换，缺了 TLS 直接握手失败
  'jdk.unsupported',      // sun.misc.Unsafe，Netty / Spring 大量使用
  'jdk.xml.dom'
]

const MIN_MAJOR = 17
const MAX_MAJOR = 24

const scriptsDir = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(scriptsDir, '..')
const runtime = path.join(frontend, 'runtime')
const target = path.join(runtime, 'jre')
const isWin = process.platform === 'win32'

const log = (m) => console.log(`[jre] ${m}`)
const fail = (m) => {
  console.error(`[jre] ${m}`)
  process.exit(1)
}

/** 从 release 文件里读 JAVA_VERSION，取主版本号；读不到返回 null */
function majorOf(releaseFile) {
  try {
    const text = fs.readFileSync(releaseFile, 'utf8')
    const m = text.match(/JAVA_VERSION="(\d+)(?:\.([\d.]+))?"/)
    if (!m) return null
    // JDK 8 是 1.8.0_xxx，主版本取第二段
    return m[1] === '1' ? Number((m[2] || '0').split('.')[0]) : Number(m[1])
  } catch {
    return null
  }
}

/** 已有的裁剪 JRE 是否可用 */
function existingOk() {
  const javaExe = path.join(target, 'bin', isWin ? 'java.exe' : 'java')
  if (!fs.existsSync(javaExe)) return false
  const major = majorOf(path.join(target, 'release'))
  return major !== null && major >= MIN_MAJOR && major <= MAX_MAJOR
}

/** 候选 JDK：环境变量优先，其次 PATH 上的 java，最后扫常见安装位置 */
function candidates() {
  const list = []
  if (process.env.INSIGHTLENS_JDK) list.push(process.env.INSIGHTLENS_JDK)
  if (process.env.JAVA_HOME) list.push(process.env.JAVA_HOME)

  // PATH 上的 java 反推 JDK 根：<root>/bin/java.exe
  const which = spawnSync(isWin ? 'where' : 'which', ['java'], { encoding: 'utf8', shell: isWin })
  const firstLine = (which.stdout || '').trim().split(/\r?\n/)[0]
  if (firstLine) list.push(path.resolve(path.dirname(firstLine), '..'))

  const home = os.homedir()
  const roots = isWin
    ? [
        'C:/Program Files/Java',
        'C:/Program Files/Eclipse Adoptium',
        'C:/Program Files/Microsoft',
        path.join(home, '.jdks'),
        path.join(home, 'scoop/apps'),
        'D:/Program Files/Java'
      ]
    : ['/usr/lib/jvm', path.join(home, '.sdkman/candidates/java')]

  for (const root of roots) {
    let entries = []
    try {
      entries = fs.readdirSync(root, { withFileTypes: true })
        .filter((d) => d.isDirectory())
        .map((d) => path.join(root, d.name))
    } catch {
      continue
    }
    list.push(...entries)
  }
  return [...new Set(list)]
}

/** 选一个能用的 jlink：必须是完整 JDK（jlink 不在 JRE 里），版本在范围内 */
function pickJdk() {
  const seen = []
  for (const dir of candidates()) {
    const jlink = path.join(dir, 'bin', isWin ? 'jlink.exe' : 'jlink')
    if (!fs.existsSync(jlink)) continue
    const major = majorOf(path.join(dir, 'release'))
    if (major === null) continue
    if (major < MIN_MAJOR || major > MAX_MAJOR) {
      seen.push(`${dir} (Java ${major}，超出 ${MIN_MAJOR}-${MAX_MAJOR})`)
      continue
    }
    log(`使用 JDK：${dir}（Java ${major}）`)
    return { dir, jlink, major }
  }
  fail(
    `找不到可用的 JDK ${MIN_MAJOR}–${MAX_MAJOR}（需要 bin/jlink）。\n` +
      `  已排查：\n${seen.map((s) => `    - ${s}`).join('\n') || '    （无候选）'}\n` +
      `  解决：安装 JDK 17+，或设置 INSIGHTLENS_JDK=<JDK 根目录> 后重试。\n` +
      `  注意 JRE 不含 jlink，必须用 JDK。`
  )
}

if (existingOk()) {
  const major = majorOf(path.join(target, 'release'))
  const mb = dirSize(target) / 1048576
  log(`已存在 Java ${major} 运行时（${mb.toFixed(0)} MB），跳过`)
  process.exit(0)
}

const { jlink, major } = pickJdk()

// 输出目录必须不存在；先建到临时目录再整体改名，避免中途失败留下半成品
fs.mkdirSync(runtime, { recursive: true })
const staging = path.join(runtime, `jre-tmp-${Date.now()}`)
fs.rmSync(staging, { recursive: true, force: true })

// --compress 语法在 JDK 21 变了：17-20 只认数字，21+ 推荐 zip-N（数字已 deprecated）
const compressArg = major >= 21 ? '--compress=zip-6' : '--compress=2'
log(`裁剪 JRE（${MODULES.length} 个模块，${compressArg}）…`)

const args = [
  '--add-modules', MODULES.join(','),
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  compressArg,
  '--output', staging
]
const r = spawnSync(jlink, args, { stdio: 'inherit' })
if (r.status !== 0) {
  fs.rmSync(staging, { recursive: true, force: true })
  fail('jlink 失败。若提示未知模块，说明该 JDK 缺模块，换一个完整 JDK 重试。')
}

const javaExe = path.join(staging, 'bin', isWin ? 'java.exe' : 'java')
if (!fs.existsSync(javaExe)) {
  fs.rmSync(staging, { recursive: true, force: true })
  fail(`jlink 产物里没有 ${javaExe}`)
}

// 覆盖旧产物：先删再改名（Windows 上不能 rename 到已存在目录）
fs.rmSync(target, { recursive: true, force: true })
fs.renameSync(staging, target)

const mb = dirSize(target) / 1048576
log(`完成：Java ${majorOf(path.join(target, 'release'))}，${mb.toFixed(0)} MB → ${target}`)

function dirSize(p) {
  let total = 0
  for (const d of fs.readdirSync(p, { withFileTypes: true })) {
    const f = path.join(p, d.name)
    total += d.isDirectory() ? dirSize(f) : fs.statSync(f).size
  }
  return total
}
