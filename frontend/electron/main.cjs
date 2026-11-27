const { app, BrowserWindow, dialog, ipcMain, shell, Menu } = require('electron')
const { spawn, spawnSync } = require('node:child_process')
const http = require('node:http')
const net = require('node:net')
const fs = require('node:fs')
const path = require('node:path')
const { setupAutoUpdate } = require('./updater.cjs')

/**
 * 两种落地形态：
 * - 开发态：__dirname = <repo>/frontend/electron，仓库根往上两级；
 * - 打包后：代码在 app.asar 里，__dirname 指向 asar 内部（不可当普通目录用），
 *   后端产物与运行时都放在 process.resourcesPath 下（见 package.json 的 extraResources）。
 * 所以所有「找文件」的路径都必须过这里，不能直接用 __dirname 推算。
 */
const DEV_ROOT = path.resolve(__dirname, '..', '..')
const APP_ROOT = app.isPackaged ? process.resourcesPath : DEV_ROOT
const BACKEND_DIR = app.isPackaged ? path.join(APP_ROOT, 'backend') : path.join(DEV_ROOT, 'target')
const JAR = process.env.INSIGHTLENS_JAR
  || path.join(BACKEND_DIR, app.isPackaged ? 'InsightLens.jar' : 'InsightLens-0.0.1-SNAPSHOT.jar')
const LOCAL_CONFIG = 'application-local.yaml'
const LOG_FILE = 'backend.log'

/**
 * 单实例 + 预热探针兜底。
 *
 * 两个实例会各自 spawn 一个 java，抢同一个 DuckDB 文件锁（第二个必然起不来），
 * 所以只允许一个。
 *
 * 预热探针：正常情况下预热是用 ELECTRON_RUN_AS_NODE=1 把同一份 exe 当 Node 跑，
 * 走不到这里。但如果打包时 runAsNode fuse 被关掉（electron-builder 26 默认关闭，
 * 已在 package.json 的 build.electronFuses 显式打开），这个子进程会变成「再启动一个
 * 完整应用」—— 检测到探针标记就直接退出，避免进程滚雪球式繁殖。
 */
const isWarmProbe = process.env.INSIGHTLENS_WARM_PROBE === '1'
const gotSingleInstanceLock = app.requestSingleInstanceLock()
if (isWarmProbe || !gotSingleInstanceLock) app.quit()
app.on('second-instance', () => {
  const win = BrowserWindow.getAllWindows().find((w) => !w.isDestroyed())
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.focus()
})

let backend = null
let apiBase = null
let logStream = null
let splash = null
// 后端进程是否已退出（退出码）：启动期间提前退出要立刻报错，而不是傻等超时
let backendExit = null
// open-websearch 是否预热成功：决定要不要给后端打开 MCP 开关
let mcpWarmed = false
// 启动看门狗：不再用「固定总时长」判超时——这台机器冷启动实测过 3 分钟以上
// （Electron 自身 80s + JVM 引导 50s + Spring 上下文 130s），任何固定上限都会误判。
// 改成「日志还在长就继续等」，只有进程退出或连续 IDLE 秒没有任何输出才判定失败。
const BACKEND_IDLE_TIMEOUT_MS = Number(process.env.INSIGHTLENS_IDLE_TIMEOUT || 120000)

/** Electron 用户数据目录即 insightlens.home：数据库、上传文件、密钥配置都在这里，天然按用户隔离 */
function home() {
  return app.getPath('userData')
}

/**
 * 首启准备：
 * 1. 建 data/excel 目录 —— DuckDB 要 data 存在，agent.workspace 指向 data/excel
 * 2. 把 application-local.yaml 复制到 userData —— 后端用 insightlens.home
 *    找配置，userData 里没有就直接启动失败（application.yaml 的 import 没写 optional）
 *    打包态的来源是安装目录 resources/application-local.yaml，它由
 *    application-local.example.yaml（空 Key 模板）打包而来，绝不能改成真实的本地配置，
 *    否则用户密钥会随安装包公开分发。
 * 已存在的文件不覆盖，避免抹掉用户改过的密钥。
 */
function prepareHome() {
  const h = home()
  fs.mkdirSync(path.join(h, 'data', 'excel'), { recursive: true })

  const dest = path.join(h, LOCAL_CONFIG)
  if (!fs.existsSync(dest)) {
    const src = app.isPackaged
      ? path.join(APP_ROOT, LOCAL_CONFIG)
      : path.join(DEV_ROOT, LOCAL_CONFIG)
    if (!fs.existsSync(src)) {
      dialog.showErrorBox(
        '缺少密钥配置',
        app.isPackaged
          ? `安装包不完整：找不到 ${src}\n请重新安装 InsightLens。`
          : `找不到 ${src}\n开发态请把 application-local.example.yaml 复制为 application-local.yaml 并填入 Key。`
      )
      return false
    }
    fs.copyFileSync(src, dest)
  }
  return true
}

/** 从 8080 起找空闲端口，避免和 IDEA 里正在跑的开发后端抢端口 */
function pickPort(start = 8080, tries = 20) {
  return new Promise((resolve, reject) => {
    function attempt(port, left) {
      const server = net.createServer()
      server.once('error', (err) => {
        if (err.code === 'EADDRINUSE') {
          if (left > 0) { attempt(port + 1, left - 1); return }
          reject(new Error(`端口 ${start}-${start + tries} 全部被占用，无法启动后端。\n请关闭占用端口的程序，或设置环境变量 INSIGHTLENS_PORT 指定端口。`))
          return
        }
        reject(err)
      })
      server.once('listening', () => server.close(() => resolve(port)))
      server.listen(port, '127.0.0.1')
    }
    attempt(start, tries)
  })
}

/**
 * 联网搜索运行时目录：开发态 frontend/runtime，打包后 resources/runtime。
 * 只放 open-websearch 本体（由 scripts/prepare-runtime.mjs 装好），不再带 node.exe。
 */
function runtimeDir() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'runtime')
    : path.join(__dirname, '..', 'runtime')
}

/**
 * 找 java 可执行体：优先用安装包内置的裁剪 JRE，其次才退回 PATH 里的 java。
 *
 * 为什么内置：此前要求用户自备 JDK 17 并加进 PATH，等于把「能不能用」押在用户环境上。
 * 内置的是 jlink 裁出来的 JRE（约 45MB，完整 JRE 180MB），模块集由
 * scripts/prepare-jre.mjs 生成，已实测覆盖 Spring Boot + DuckDB 原生库 + HTTPS TLS。
 *
 * 开发态不强制：frontend/runtime/jre 存在就用，不存在就用系统 java —— 改后端代码时
 * 不该被迫先跑一遍 jlink。打包前 predist 会确保它存在。
 */
function javaExe() {
  const bundled = path.join(runtimeDir(), 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  return fs.existsSync(bundled) ? bundled : 'java'
}

/**
 * 找到能跑 open-websearch 的 Node 可执行体。
 *
 * 首选 Electron 自己：加上 ELECTRON_RUN_AS_NODE=1，electron.exe 就是一个纯 Node
 * （Electron 35.7.5 → Node 22.16.0），于是 83MB 的独立 node.exe 完全不用打包。
 * open-websearch 唯一的原生依赖 koffi 是 N-API 模块（napi 10），不受 Node/Electron
 * ABI 差异影响 —— 已在本机用 electron.exe 实跑 `search` 验证通。
 *
 * 若 runtime/node 里确实带过独立 node（历史产物，或将来遇到不兼容平台），优先用它：
 * 零额外环境变量，出问题也好回退。
 */
function nodeRuntime() {
  const rt = runtimeDir()
  const script = path.join(rt, 'mcp', 'node_modules', 'open-websearch', 'build', 'index.js')
  if (!fs.existsSync(script)) return null

  const bundled = path.join(rt, 'node', process.platform === 'win32' ? 'node.exe' : 'node')
  if (fs.existsSync(bundled)) return { exe: bundled, env: {}, script, kind: 'bundled' }

  // 用纯 node 跑主进程调试时（没有 electron 版本号）借不到 Electron，直接放弃
  if (!process.versions.electron) return null
  return { exe: process.execPath, env: { ELECTRON_RUN_AS_NODE: '1' }, script, kind: 'electron' }
}

/** 预热结果既要在终端可见，也要落 backend.log —— 打包后没有终端，全靠日志排障 */
function mcpLog(msg) {
  appLog(`[mcp] ${msg}`)
}

/**
 * 通用日志通道：终端 + backend.log 双写。
 * 打包后没有终端，用户报问题时只能靠这个文件，所以每条异常路径都要落一笔。
 */
function appLog(msg) {
  const line = `${msg}\n`
  try { process.stdout.write(line) } catch (_) { /* 打包后无终端 */ }
  if (logStream) logStream.write(line)
  else try { fs.appendFileSync(path.join(home(), LOG_FILE), line, 'utf8') } catch (_) { /* 目录只读 */ }
}

/**
 * 传给后端的 MCP 参数。
 *
 * 必须「文件存在 + 预热成功」才敢打开开关：Spring AI 的
 * McpToolCallbackAutoConfiguration 在上下文刷新时就要拿到工具列表，stdio 子进程
 * 起不来会让整个 ApplicationContext 创建失败（ReActAgentRunner 上的
 * required = false 挡不住，那个只管注入不管 bean 创建）。
 */
function mcpArgs() {
  const rt = nodeRuntime()

  if (!rt) {
    mcpLog('运行时不全，联网搜索已禁用（重装或执行 npm run runtime 可修复）')
    return ['-Dinsightlens.mcp.enabled=false']
  }

  if (!mcpWarmed) {
    mcpLog('预热未完成，本次启动不启用联网搜索（应用功能不受影响，下次启动会自动重试）')
    return ['-Dinsightlens.mcp.enabled=false']
  }

  // INSIGHTLENS_MCP_ENABLED=false 可强制关掉（排查启动慢时用）
  const off = String(process.env.INSIGHTLENS_MCP_ENABLED || '').toLowerCase() === 'false'
  return [
    `-Dinsightlens.mcp.enabled=${off ? 'false' : 'true'}`,
    `-Dinsightlens.mcp.node=${rt.exe}`,
    `-Dinsightlens.mcp.websearch-script=${rt.script}`
  ]
}

/**
 * 预热结果的磁盘缓存：<userData>/mcp-warm.json
 *
 * 为什么要缓存：预热本身要起一次子进程加载 6000 多个文件，稳定在 1-4 秒，
 * 却每次都发生在「后端启动之前」，等于每次开机白等。缓存命中后直接跳过，
 * 只在「首次运行 / 升级 Electron / 重装依赖」时真正预热一次 —— 这三种情况
 * 恰好就是页缓存失效的情况，逻辑上是等价的。
 *
 * fail 计数用于熔断：连续 2 次预热失败（缺 DLL、杀软拦截、磁盘极冷）就长期
 * 关闭搜索，避免每次开机都白等 20 秒超时。删掉这个 json 即可重试。
 */
const WARM_CACHE_FILE = 'mcp-warm.json'
const WARM_MAX_FAILS = 2

function runtimeKey(rt) {
  try {
    const st = fs.statSync(rt.script)
    return `${process.versions.electron || 'dev'}|${st.size}|${Math.floor(st.mtimeMs)}`
  } catch (_) {
    return null
  }
}

function readWarmCache() {
  try {
    return JSON.parse(fs.readFileSync(path.join(home(), WARM_CACHE_FILE), 'utf8'))
  } catch (_) {
    return null
  }
}

function writeWarmCache(value) {
  try {
    fs.writeFileSync(path.join(home(), WARM_CACHE_FILE), JSON.stringify(value), 'utf8')
  } catch (_) { /* 写不进去只是下次多预热一次，不影响功能 */ }
}

/**
 * 预热 open-websearch：起一次 stdio 子进程，等它打出 "STDIO transport enabled" 再杀掉。
 *
 * 为什么必须预热：MCP Java SDK 的 initialize 有个 20 秒硬超时（initializationTimeout），
 * Spring AI 1.1.2 没把它暴露成配置项（只暴露了 request-timeout，管不到 initialize）。
 * open-websearch 有 6000 多个依赖文件，磁盘冷的时候首次加载实测能超过 20 秒，
 * 一旦超时整个后端就起不来。预热把页缓存和杀软扫描都过一遍，之后真正那次握手
 * 实测 0.15-4 秒。
 *
 * 失败只是本次不启用联网搜索，不影响启动。
 */
async function warmUpWebSearch(timeoutMs = 20000) {
  const rt = nodeRuntime()
  if (!rt) {
    mcpLog('运行时缺失，本次不启用联网搜索')
    return false
  }

  const key = runtimeKey(rt)
  const cache = readWarmCache()

  if (key && cache && cache.key === key) {
    if (cache.ok) return true
    if ((cache.fails || 0) >= WARM_MAX_FAILS) {
      mcpLog(`预热已连续失败 ${cache.fails} 次，本次跳过；删除 ${WARM_CACHE_FILE} 可重试`)
      return false
    }
  }

  const ok = await spawnWarm(rt, timeoutMs)
  if (key) {
    const fails = ok ? 0 : (cache && cache.key === key ? (cache.fails || 0) : 0) + 1
    writeWarmCache({ key, ok, fails, at: Date.now() })
  }
  if (!ok) mcpLog(`预热失败（${timeoutMs}ms 内未就绪），本次不启用联网搜索`)
  return ok
}

function spawnWarm(rt, timeoutMs) {
  return new Promise((resolve) => {
    let child = null
    let settled = false
    const finish = (ok) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      // 这里的进程树里只有它自己，直接 kill 足够
      try { if (child) child.kill() } catch (_) { /* 已退出 */ }
      resolve(ok)
    }
    const timer = setTimeout(() => finish(false), timeoutMs)

    try {
      child = spawn(rt.exe, [rt.script], {
        // rt.env 不能漏：借 Electron 当 Node 时少了 ELECTRON_RUN_AS_NODE 会弹出一个 Chromium 空窗口。
        // 探针标记：万一 fuse 没生效，子进程会以完整应用形态启动，这个标记让它立刻退出。
        env: {
          ...process.env,
          ...rt.env,
          MODE: 'stdio',
          DEFAULT_SEARCH_ENGINE: 'baidu',
          INSIGHTLENS_WARM_PROBE: '1'
        },
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe']
      })
    } catch (_) {
      finish(false)
      return
    }

    // open-websearch 把启动日志打在 stderr，两路都听，避免版本差异导致漏判
    const onData = (buf) => {
      if (String(buf).includes('STDIO transport enabled')) finish(true)
    }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.on('error', () => finish(false))
    child.on('exit', () => finish(false))
  })
}

/**
 * 启动期 JVM 参数：只放「确定有用、且不影响功能正确性」的开关。
 * - -Xms/-Xmx 写死：免得每次启动按物理内存推算初始堆。
 * - 关 PerfData / JMX / banner：启动阶段没人看的几件事，顺手省掉。
 *
 * 试过但没有保留的两项（别再走回头路）：
 * - -XX:TieredStopAtLevel=1：实测启动耗时差异在噪声内，瓶颈不在 JIT，
 *   却会让长跑的 SQL 解析变慢；
 * - -XX:+AutoCreateSharedArchive：归档只在 JVM 正常退出时才落盘，
 *   而桌面端关闭是 kill 子进程，archive 永远不生成，等于白配。
 */
function jvmArgs(port) {
  return [
    '-Dfile.encoding=UTF-8',
    `-Dinsightlens.home=${home()}`,
    `-Dserver.port=${port}`,
    '-Xms256m',
    '-Xmx1g',
    '-XX:-UsePerfData',
    '-Dspring.jmx.enabled=false',
    '-Dspring.main.banner-mode=off',
    ...mcpArgs()
  ]
}

/**
 * 后端有两种可运行形态，优先用解包形态：
 * 1) target/classes + target/lib：类从普通目录、普通 jar 读。fat jar 里 160 个依赖是
 *    嵌套 zip，每次类查找都要在 150MB 的包内定位 —— 实测解包形态比 -jar 快约 3 秒。
 * 2) fat jar：解包产物不全时回退，保证「只有 jar 也能启动」。
 */
function backendLaunch() {
  const classes = path.join(BACKEND_DIR, 'classes')
  const lib = path.join(BACKEND_DIR, 'lib')
  const entry = path.join(classes, 'com', 'gdou', 'agent', 'InsightLensApplication.class')
  if (fs.existsSync(entry) && fs.existsSync(lib)) {
    const cp = [classes, path.join(lib, '*')].join(path.delimiter)
    return ['-cp', cp, 'com.gdou.agent.InsightLensApplication']
  }
  if (fs.existsSync(JAR)) return ['-jar', JAR]
  return null
}

function startBackend(port) {
  const launch = backendLaunch()
  if (!launch) {
    dialog.showErrorBox(
      '后端未构建',
      `找不到 ${JAR}\n${app.isPackaged ? '安装包不完整，请重新安装。' : `请先在 ${DEV_ROOT} 执行 mvnw package`}`
    )
    return false
  }

  const rt = nodeRuntime()
  const java = javaExe()
  appLog(`[backend] java=${java}`)
  logStream = fs.createWriteStream(path.join(home(), LOG_FILE), { flags: 'a' })
  backendExit = null
  backend = spawn(java, [...jvmArgs(port), ...launch], {
    cwd: APP_ROOT,
    windowsHide: true,
    // ELECTRON_RUN_AS_NODE 必须由 java 继承下去：Spring AI 用 ProcessBuilder 起搜索子进程，
    // 而它的可执行体就是 Electron 自己（见 nodeRuntime），没有这个变量它会去开 Chromium 窗口。
    env: { ...process.env, ...(rt ? rt.env : {}) }
  })

  // 后端日志同时打到终端和 userData/backend.log：以前 stdio:'ignore' 让启动失败无任何线索
  const pipe = (buf) => {
    const text = buf.toString('utf8')
    try { process.stdout.write(text) } catch (_) { /* 打包后可能没有终端 */ }
    logStream.write(text)
  }
  backend.stdout.on('data', pipe)
  backend.stderr.on('data', pipe)
  backend.on('exit', (code, signal) => {
    backendExit = code === null ? `signal ${signal}` : code
  })
  backend.on('error', (err) => {
    backendExit = err.message
    let hint = ''
    if (err.code === 'ENOENT') {
      // 打包态 javaExe() 一定返回绝对路径；走了 PATH 说明内置运行时没生成
      hint = path.isAbsolute(java)
        ? '内置 Java 运行时缺失或被拦截，请重新安装（杀毒软件可能隔离了 runtime\\jre）'
        : '本机没有找到 java。安装 JDK 17 并加入 PATH，或在 frontend 目录执行 npm run jre 生成内置运行时'
    }
    dialog.showErrorBox('后端启动失败', `${err.message}${hint ? '\n' + hint : ''}`)
  })
  return true
}

/** 读日志尾部若干行，启动失败时直接展示给用户，不用自己去翻文件 */
function logTail(lines = 25) {
  try {
    const p = path.join(home(), LOG_FILE)
    if (!fs.existsSync(p)) return '(还没有日志)'
    const text = fs.readFileSync(p, 'utf8')
    return text.trimEnd().split('\n').slice(-lines).join('\n')
  } catch (err) {
    return `(读取日志失败: ${err.message})`
  }
}

/** 启动期间的过渡窗口：以前是纯黑屏干等，看起来就像「启动失败」 */
function createSplash() {
  splash = new BrowserWindow({
    width: 460,
    height: 200,
    frame: false,
    resizable: false,
    center: true,
    show: true,
    backgroundColor: '#14161a',
    webPreferences: { nodeIntegration: false, contextIsolation: true }
  })
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
    background:#14161a;color:#e8eaed;font-family:"Microsoft YaHei",system-ui,sans-serif;user-select:none}
    .bar{width:220px;height:3px;background:#2a2f38;border-radius:2px;overflow:hidden;margin-top:18px}
    .bar i{display:block;width:40%;height:100%;background:#d97a45;animation:m 1.2s ease-in-out infinite}
    @keyframes m{0%{transform:translateX(-100%)}100%{transform:translateX(350%)}}
    h1{font-size:16px;font-weight:600;margin:0;letter-spacing:.5px}
    p{font-size:12px;color:#8b929d;margin:8px 0 0}
    .sec{font-size:12px;color:#5f6672;margin-top:14px;font-variant-numeric:tabular-nums}
  </style></head><body>
    <h1>InsightLens 正在启动</h1>
    <p>正在加载数据服务，通常 10-20 秒（首次建库稍久）</p>
    <div class="bar"><i></i></div>
    <div class="sec">已等待 <span id="s">0</span> 秒</div>
    <script>
      let n = 0
      setInterval(() => { document.getElementById('s').textContent = ++n }, 1000)
    </script>
  </body></html>`
  splash.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html))
}

function closeSplash() {
  if (splash && !splash.isDestroyed()) splash.close()
  splash = null
}

function logFileSize() {
  try { return fs.statSync(path.join(home(), LOG_FILE)).size } catch (_) { return 0 }
}

/**
 * 后端就绪检测：按「日志是否还在推进」判活，而不是按固定时长。
 * - 进程退出（崩溃/端口被占/找不到 jar）→ 立刻失败并带上日志尾部
 * - 连续 idleTimeoutMs 无任何新日志 → 认为卡死，失败并带上日志尾部
 * - 只要后端还在输出，就一直等，多慢的冷启动都不会误报
 */
function waitForBackend(idleTimeoutMs = BACKEND_IDLE_TIMEOUT_MS) {
  const startedAt = Date.now()
  let lastSize = logFileSize()
  let lastProgressAt = Date.now()
  return new Promise((resolve, reject) => {
    function check() {
      if (backendExit !== null) {
        reject(new Error(`后端进程已退出（${backendExit}）。\n\n日志尾部：\n${logTail()}`))
        return
      }
      const size = logFileSize()
      if (size !== lastSize) {
        lastSize = size
        lastProgressAt = Date.now()
      }
      if (Date.now() - lastProgressAt > idleTimeoutMs) {
        reject(new Error(
          `后端已 ${Math.round(idleTimeoutMs / 1000)} 秒没有任何输出，判定为卡住（已等待 ${Math.round((Date.now() - startedAt) / 1000)} 秒）。\n\n`
          + `日志文件：${path.join(home(), LOG_FILE)}\n\n日志尾部：\n${logTail()}`
        ))
        return
      }
      const req = http.get(`${apiBase}/personal/config`, (res) => {
        res.resume()
        if (res.statusCode === 200) resolve()
        else setTimeout(check, 500)
      })
      req.on('error', () => setTimeout(check, 500))
      req.setTimeout(1500, () => req.destroy())
    }
    check()
  })
}

/** 后端已托管 Vue 构建产物，渲染进程直接 loadURL，无需 VITE_API_BASE */
function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    title: 'InsightLens',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })
  // 渲染进程报错以前完全看不到（主进程日志里没有），白屏时无从排查。
  // 这里把 console / 加载失败都转进同一个日志文件。
  win.webContents.on('console-message', (event, level, message) => {
    const text = `[renderer] ${message}\n`
    if (logStream) logStream.write(text)
    try { process.stdout.write(text) } catch (_) { /* 打包后无终端 */ }
  })
  win.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
    const text = `[renderer] 页面加载失败 ${errorCode} ${errorDescription}\n`
    if (logStream) logStream.write(text)
  })
  win.loadURL(apiBase)
}

/** 只允许打开 userData 内的文件，防止渲染进程被注入后读取任意路径 */
function safePath(requested) {
  const root = path.resolve(home())
  const target = path.resolve(String(requested || ''))
  if (target !== root && !target.startsWith(root + path.sep)) return null
  return target
}

ipcMain.handle('file:open', async (_event, requested) => {
  const target = safePath(requested)
  if (!target) return '只允许打开工作区内的文件'
  return shell.openPath(target)
})

ipcMain.handle('file:show', (_event, requested) => {
  const target = safePath(requested)
  if (!target) return '只允许定位工作区内的文件'
  shell.showItemInFolder(target)
  return ''
})

function stopBackend() {
  if (backend && !backend.killed) {
    // 必须杀进程树：java 下面还挂着 open-websearch 的 node 子进程（MCP stdio）。
    // child.kill() 只结束 java 本身，node 会变成孤儿常驻，下次启动还被它的端口/文件占用。
    try {
      spawnSync('taskkill', ['/pid', String(backend.pid), '/T', '/F'], { windowsHide: true })
    } catch (_) {
      backend.kill()
    }
  }
  if (logStream) { logStream.end(); logStream = null }
  closeSplash()
}

app.whenReady().then(async () => {
  // 预热探针 / 拿不到单实例锁：直接退出，别再走一遍起后端的流程
  if (isWarmProbe || !gotSingleInstanceLock) return
  // 隐藏 Electron 默认的 File/Edit/View 菜单栏，桌面应用不该露出开发者菜单
  Menu.setApplicationMenu(null)
  try {
    // prepareHome 以前在 try 之外：一旦 mkdir/复制抛错就是未捕获异常，
    // 进程活着但不弹窗不开窗，表现和「启动失败」一模一样且毫无线索
    if (!prepareHome()) { app.quit(); return }
    createSplash()
    // 预热与选端口并行：预热只影响「要不要开联网搜索」，不该拖慢主启动路径
    const portPromise = process.env.INSIGHTLENS_PORT
      ? Promise.resolve(Number(process.env.INSIGHTLENS_PORT))
      : pickPort()
    const [port, warm] = await Promise.all([portPromise, warmUpWebSearch()])
    mcpWarmed = warm
    apiBase = `http://127.0.0.1:${port}`
    if (!startBackend(port)) { app.quit(); return }
    await waitForBackend()
    closeSplash()
    createWindow()
    // 更新检查放在窗口可见之后，且内部还有延迟：启动路径上不能有网络等待
    setupAutoUpdate({ log: appLog, beforeInstall: stopBackend })
  } catch (err) {
    closeSplash()
    dialog.showErrorBox('InsightLens 启动失败', err.message)
    app.quit()
  }
})

app.on('window-all-closed', () => { stopBackend(); app.quit() })
app.on('before-quit', stopBackend)
