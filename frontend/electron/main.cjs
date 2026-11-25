const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron')
const { spawn } = require('node:child_process')
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')

const PORT = process.env.INSIGHTLENS_PORT || 8080
const API_BASE = `http://127.0.0.1:${PORT}`
const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const JAR = process.env.INSIGHTLENS_JAR || path.join(PROJECT_ROOT, 'target', 'InsightLens-0.0.1-SNAPSHOT.jar')

let backend = null

/** Electron 的用户数据目录即 Excel 工作区，保证安装后每个用户独立 */
function workspaceRoot() {
  return app.getPath('userData')
}

function startBackend() {
  if (!fs.existsSync(JAR)) {
    dialog.showErrorBox('后端未构建', `找不到 ${JAR}\n请先在 ${PROJECT_ROOT} 执行 mvnw package`)
    return false
  }
  backend = spawn('java', [
    '-Dfile.encoding=UTF-8',
    `-Dinsightlens.home=${workspaceRoot()}`,
    '-jar', JAR
  ], { cwd: PROJECT_ROOT, stdio: 'ignore', windowsHide: true })
  backend.on('error', (err) => {
    dialog.showErrorBox('后端启动失败', err.message)
  })
  return true
}

/** 后端启动后 Spring Boot 已经托管了 Vue 构建产物，渲染进程直接 loadURL 即可 */
function waitForBackend(retries = 100) {
  return new Promise((resolve, reject) => {
    function check() {
      const req = http.get(`${API_BASE}/personal/config`, (res) => {
        res.resume()
        if (res.statusCode === 200) resolve()
        else retry()
      })
      req.on('error', retry)
      req.setTimeout(1000, () => req.destroy())
    }
    function retry() {
      if (--retries <= 0) reject(new Error('后端启动超时，请检查 8080 端口是否被占用'))
      else setTimeout(check, 300)
    }
    check()
  })
}

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
  win.loadURL(API_BASE)
}

/** 只允许打开工作区内的文件，防止渲染进程被注入后读取任意路径 */
function safePath(requested) {
  const root = path.resolve(workspaceRoot())
  const target = path.resolve(String(requested || ''))
  if (target !== root && !target.startsWith(root + path.sep)) {
    return null
  }
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

app.whenReady().then(async () => {
  if (!startBackend()) return
  try {
    await waitForBackend()
    createWindow()
  } catch (err) {
    dialog.showErrorBox('InsightLens 启动失败', err.message)
    app.quit()
  }
})

app.on('window-all-closed', () => {
  if (backend && !backend.killed) backend.kill()
  app.quit()
})

app.on('before-quit', () => {
  if (backend && !backend.killed) backend.kill()
})
