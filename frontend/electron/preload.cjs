const { contextBridge, ipcRenderer } = require('electron')

// 渲染进程只能拿到这两个方法；工作区路径由后端 /personal/config 提供，
// 不需要把 Node 的 app.getPath 暴露给页面。
contextBridge.exposeInMainWorld('electronAPI', {
  openFile: (absPath) => ipcRenderer.invoke('file:open', absPath),
  showItemInFolder: (absPath) => ipcRenderer.invoke('file:show', absPath)
})
