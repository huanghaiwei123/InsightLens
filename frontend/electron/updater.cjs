'use strict'

/**
 * 自动更新（electron-updater + GitHub Releases）
 *
 * 设计原则：更新失败绝不影响使用。
 * - 只在打包态（app.isPackaged）启用，开发态直接跳过
 * - 检查失败只写日志、不弹窗：GitHub 在国内经常连不上，弹窗会变成每次启动的骚扰
 * - 下载前问一次、安装前问一次，不静默重启（本地还挂着后端进程和 DuckDB 文件）
 *
 * 为什么安装前必须先停后端：安装器要覆盖 resources\backend 下的 classes/lib，
 * 而 java 进程正把这些文件握着，不停就是「文件被占用」导致装完还是旧版本。
 */

const { app, dialog, BrowserWindow } = require('electron')

// 想换更新源（自建对象存储 / 内网测试）时设这个环境变量，走 generic provider
const FEED_OVERRIDE = process.env.INSIGHTLENS_UPDATE_FEED || ''
const CHECK_DELAY_MS = Number(process.env.INSIGHTLENS_UPDATE_DELAY || 5000)

function mainWindow() {
  return BrowserWindow.getAllWindows().find((w) => !w.isDestroyed() && w.isVisible()) || null
}

function ask(parent, options) {
  // 没有窗口时 dialog.showMessageBox(null, ...) 会抛，退化成无父窗口的模态框
  return parent ? dialog.showMessageBox(parent, options) : dialog.showMessageBox(options)
}

/**
 * @param {object} opts
 * @param {(msg: string) => void} opts.log      写进 backend.log，便于用户事后排查
 * @param {() => void} opts.beforeInstall       安装前停止后端（杀 java 进程树）
 */
function setupAutoUpdate({ log, beforeInstall }) {
  if (!app.isPackaged) {
    log('[update] 开发态，跳过自动更新')
    return
  }
  if (process.env.INSIGHTLENS_NO_UPDATE === '1') {
    log('[update] INSIGHTLENS_NO_UPDATE=1，跳过自动更新')
    return
  }

  let autoUpdater
  try {
    ;({ autoUpdater } = require('electron-updater'))
  } catch (err) {
    log(`[update] electron-updater 不可用，跳过自动更新：${err.message}`)
    return
  }

  // electron-updater 默认往 stdout/文件写自己的日志，这里统一收进 backend.log
  const pipe = (level) => (msg) => log(`[update][${level}] ${msg}`)
  autoUpdater.logger = {
    info: pipe('info'),
    warn: pipe('warn'),
    error: pipe('error'),
    debug: pipe('debug')
  }
  autoUpdater.autoDownload = false // 先问用户再下
  autoUpdater.autoInstallOnAppQuit = true // 选了「退出时安装」靠这个生效
  autoUpdater.allowPrerelease = false
  autoUpdater.allowDowngrade = false

  if (FEED_OVERRIDE) {
    try {
      autoUpdater.setFeedURL({ provider: 'generic', url: FEED_OVERRIDE })
      log(`[update] 使用自定义更新源 ${FEED_OVERRIDE}`)
    } catch (err) {
      log(`[update] 自定义更新源设置失败，回退到打包内置源：${err.message}`)
    }
  }

  autoUpdater.on('checking-for-update', () => {
    log(`[update] 开始检查，当前版本 ${app.getVersion()}`)
  })

  autoUpdater.on('update-not-available', (info) => {
    log(`[update] 已是最新（远端 ${info && info.version}）`)
  })

  autoUpdater.on('update-available', async (info) => {
    log(`[update] 发现新版本 ${info.version}（当前 ${app.getVersion()}）`)
    let response = 1
    try {
      ;({ response } = await ask(mainWindow(), {
        type: 'info',
        title: '发现新版本',
        message: `InsightLens ${info.version} 已发布`,
        detail:
          `当前版本 ${app.getVersion()}，新版本 ${info.version}。\n\n` +
          '下载期间可以继续使用，完成后会再问一次要不要立即重启。',
        buttons: ['下载更新', '稍后'],
        defaultId: 0,
        cancelId: 1,
        noLink: true
      }))
    } catch (err) {
      log(`[update] 弹窗失败（${err.message}），自动跳过本次更新`)
      return
    }
    if (response !== 0) {
      log('[update] 用户选择稍后')
      return
    }
    try {
      await autoUpdater.downloadUpdate()
    } catch (err) {
      log(`[update] 下载失败：${err.message}`)
      const win = mainWindow()
      if (win) win.setProgressBar(-1)
      ask(win, {
        type: 'error',
        title: '更新下载失败',
        message: '新版本没能下载完',
        detail: `${err.message}\n\n不影响当前使用，下次启动会重新检查。`,
        buttons: ['好'],
        noLink: true
      }).catch(() => {})
    }
  })

  autoUpdater.on('download-progress', (p) => {
    const win = mainWindow()
    // 任务栏进度条，不额外弹窗打断
    if (win) win.setProgressBar(Math.min(1, Math.max(0, p.percent / 100)))
  })

  autoUpdater.on('update-downloaded', async (info) => {
    const win = mainWindow()
    if (win) win.setProgressBar(-1)
    log(`[update] ${info.version} 下载完成，等待用户决定何时安装`)

    let response = 1
    try {
      ;({ response } = await ask(win, {
        type: 'info',
        title: '更新已就绪',
        message: `InsightLens ${info.version} 已下载完成`,
        detail:
          '「立即重启」会关闭窗口、停止本地数据服务并安装。\n' +
          '「退出时安装」则等你下次正常关闭应用时再装。\n\n' +
          '两种方式都不会影响你的数据（工作区文件不会被改动）。',
        buttons: ['立即重启', '退出时安装'],
        defaultId: 0,
        cancelId: 1,
        noLink: true
      }))
    } catch (err) {
      log(`[update] 弹窗失败（${err.message}），改为退出时安装`)
      return
    }

    if (response !== 0) {
      log('[update] 用户选择退出时安装')
      return
    }

    log('[update] 用户选择立即重启，先停止后端再交给安装器')
    // 关键顺序：先杀 java（释放 resources\backend 的文件句柄），再 quitAndInstall。
    // 反过来的话 NSIS 会因为「文件正在使用」删不掉旧版本。
    try {
      beforeInstall()
    } catch (err) {
      log(`[update] 停止后端出错（继续安装）：${err.message}`)
    }
    // isSilent=false, isForceRunAfter=true：显示安装界面并在装完后自动启动
    autoUpdater.quitAndInstall(false, true)
  })

  autoUpdater.on('error', (err) => {
    const win = mainWindow()
    if (win) win.setProgressBar(-1)
    // 不打用户：断网、GitHub 被墙、代理配置错误都会走到这里，本地功能完全不受影响
    log(`[update] 更新检查出错（不影响使用）：${(err && err.message) || err}`)
  })

  // 等窗口和后端都稳定了再查，避免和启动抢磁盘/网络
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch((err) => {
      log(`[update] 检查请求失败（不影响使用）：${err && err.message ? err.message : err}`)
    })
  }, CHECK_DELAY_MS)
}

module.exports = { setupAutoUpdate }
