const { app, BrowserWindow, dialog, ipcMain, shell, Menu } = require('electron')
const { spawn } = require('node:child_process')
const http = require('node:http')
const net = require('node:net')
const fs = require('node:fs')
const path = require('node:path')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const JAR = process.env.INSIGHTLENS_JAR || path.join(PROJECT_ROOT, 'target', 'InsightLens-0.0.1-SNAPSHOT.jar')
const LOCAL_CONFIG = 'application-local.yaml'
const LOG_FILE = 'backend.log'

let backend = null
let apiBase = null
let logStream = null

/** Electron 用户数据目录即 insightlens.home：数据库、上传文件、密钥配置都在这里，天然按用户隔离 */
function home() {
  return app.getPath('userData')
}

/**
 * 首启准备：
 * 1. 建 data/excel 目录 —— DuckDB 要 data 存在，agent.workspace 指向 data/excel
 * 2. 把项目根目录的 application-local.yaml 复制到 userData —— 后端用 insightlens.home
 *    找配置，userData 里没有就直接启动失败（application.yaml 的 import 没写 optional）
 * 已存在的文件不覆盖，避免抹掉用户改过的密钥。
 */
function prepareHome() {
  const h = home()
  fs.mkdirSync(path.join(h, 'data', 'excel'), { recursive: true })

  const dest = path.join(h, LOCAL_CONFIG)
  if (!fs.existsSync(dest)) {
    const src = path.join(PROJECT_ROOT, LOCAL_CONFIG)
    if (!fs.existsSync(src)) {
      dialog.showErrorBox(
        '缺少密钥配置',
        `找不到 ${src}\n\n首次启动需要把项目根目录的 application-local.yaml 复制到用户数据目录：\n${h}`
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
        if (err.code === 'EADDRINUSE' && left > 0) attempt(port + 1, left - 1)
        else reject(err)
      })
      server.once('listening', () => server.close(() => resolve(port)))
      server.listen(port, '127.0.0.1')
    }
    attempt(start, tries)
  })
}

function startBackend(port) {
  if (!fs.existsSync(JAR)) {
    dialog.showErrorBox('后端未构建', `找不到 ${JAR}\n请先在 ${PROJECT_ROOT} 执行 mvnw package`)
    return false
  }

  logStream = fs.createWriteStream(path.join(home(), LOG_FILE), { flags: 'a' })
  backend = spawn('java', [
    '-Dfile.encoding=UTF-8',
    `-Dinsightlens.home=${home()}`,
    `-Dserver.port=${port}`,
    '-jar', JAR
  ], { cwd: PROJECT_ROOT, windowsHide: true })

  // 后端日志同时打到终端和 userData/backend.log：以前 stdio:'ignore' 让启动失败无任何线索
  const pipe = (buf) => {
    const text = buf.toString('utf8')
    try { process.stdout.write(text) } catch (_) { /* 打包后可能没有终端 */ }
    logStream.write(text)
  }
  backend.stdout.on('data', pipe)
  backend.stderr.on('data', pipe)
  backend.on('error', (err) => {
    dialog.showErrorBox('后端启动失败', `${err.message}\n请确认已安装 JDK 17 并在 PATH 中`)
  })
  return true
}

/** 后端就绪检测；超时提示去读日志文件，不再只说「端口被占用」这种误导性文案 */
function waitForBackend(retries = 150) {
  return new Promise((resolve, reject) => {
    function check() {
      const req = http.get(`${apiBase}/personal/config`, (res) => {
        res.resume()
        if (res.statusCode === 200) resolve()
        else retry()
      })
      req.on('error', retry)
      req.setTimeout(1000, () => req.destroy())
    }
    function retry() {
      if (--retries <= 0) {
        reject(new Error(`后端启动超时（${apiBase}）。\n请查看日志：${path.join(home(), LOG_FILE)}`))
      } else {
        setTimeout(check, 300)
      }
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
  if (backend && !backend.killed) backend.kill()
  if (logStream) { logStream.end(); logStream = null }
}

app.whenReady().then(async () => {
  // 隐藏 Electron 默认的 File/Edit/View 菜单栏，桌面应用不该露出开发者菜单
  Menu.setApplicationMenu(null)
  if (!prepareHome()) { app.quit(); return }
  try {
    const port = process.env.INSIGHTLENS_PORT
      ? Number(process.env.INSIGHTLENS_PORT)
      : await pickPort()
    apiBase = `http://127.0.0.1:${port}`
    if (!startBackend(port)) { app.quit(); return }
    await waitForBackend()
    createWindow()
  } catch (err) {
    dialog.showErrorBox('InsightLens 启动失败', err.message)
    app.quit()
  }
})

app.on('window-all-closed', () => { stopBackend(); app.quit() })
app.on('before-quit', stopBackend)
