// 后端地址：Web 形态为空（相对路径，同源），Electron 形态由 VITE_API_BASE 注入。
const BASE = typeof __API_BASE__ !== 'undefined' ? __API_BASE__ : ''
const PREFIX = BASE + '/personal'

/**
 * 统一 Result：{ code, msg, data }。code 非 "200" 视为业务失败。
 * 失败时优先展示 data 里的业务消息（显式业务码的异常会回显），msg 只是通用兜底。
 */
function errorMessage(payload, fallback) {
  const data = payload && typeof payload.data === 'string' ? payload.data : ''
  const msg = payload && typeof payload.msg === 'string' ? payload.msg : ''
  return data || msg || fallback
}

async function unwrap(res) {
  let payload
  try {
    payload = await res.json()
  } catch (e) {
    throw new Error('服务返回的内容无法解析（HTTP ' + res.status + '）')
  }
  if (!res.ok || String(payload.code) !== '200') {
    throw new Error(errorMessage(payload, '请求失败（HTTP ' + res.status + '）'))
  }
  return payload.data
}

export function createSession(categoryId) {
  const q = categoryId != null ? '?categoryId=' + encodeURIComponent(categoryId) : ''
  return fetch(PREFIX + '/create' + q).then(unwrap)
}

export function fetchReport() {
  return fetch(PREFIX + '/report/categories').then(unwrap)
}

export function fetchConfig() {
  return fetch(PREFIX + '/config').then(unwrap)
}

/** 读取模型配置：apiKey 是掩码后的字符串，明文不会下发到前端。 */
export function fetchApiKeySettings() {
  return fetch(PREFIX + '/settings/api-key').then(unwrap)
}

/** 修改 API Key：保存后立即生效，不需要重启后端。 */
export function saveApiKey(apiKey) {
  return fetch(PREFIX + '/settings/api-key', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey })
  }).then(unwrap)
}

/** 按会话读历史消息：返回 [{role:'user'|'assistant', text}]，按时间升序 */
export function fetchHistory(sessionId) {
  return fetch(PREFIX + '/history?sessionId=' + encodeURIComponent(sessionId)).then(unwrap)
}

/** 报表里点文件名：浏览器形态直接走后端流式下载。 */
export function excelDownloadUrl(uploadId) {
  return PREFIX + '/file?uploadId=' + encodeURIComponent(uploadId)
}

/**
 * 下载 Excel：成功时触发浏览器保存；失败时后端返回 Result JSON，
 * 这里解析成 Error，避免用户看到一页JSON而不是错误提示。
 */
export function downloadExcel(uploadId, fileName) {
  return fetch(excelDownloadUrl(uploadId)).then(async (res) => {
    const contentType = res.headers.get('content-type') || ''
    if (!res.ok || contentType.indexOf('application/json') !== -1) {
      let payload = {}
      try { payload = await res.json() } catch (e) { /* 原样兜底 */ }
      throw new Error(errorMessage(payload, '下载失败（HTTP ' + res.status + '）'))
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName || 'excel.xlsx'
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  })
}

/**
 * Electron 形态调用主进程用系统应用打开本地 Excel。
 * 返回 null 表示当前不是 Electron 环境，调用方应回退到浏览器下载。
 */
export function openLocalExcel(absPath) {
  const bridge = window.electronAPI
  if (!bridge || typeof bridge.openFile !== 'function') return null
  return bridge.openFile(absPath)
}

/**
 * 流式对话：SSE 逐 token 返回，不能用 EventSource（后端要求 POST）。
 * onToken(text) 每帧回调，onNode(node) 节点开始，onDone() 结束，onError(msg) 出错。
 * 返回 abort 函数供取消。
 */
export function chatStream(userMsg, sessionId, { onToken, onNode, onDone, onError } = {}) {
  const controller = new AbortController()
  const url = PREFIX + '/chat/stream?userMsg=' + encodeURIComponent(userMsg)
      + '&sessionId=' + encodeURIComponent(sessionId)

  fetch(url, {
    method: 'POST',
    headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' },
    signal: controller.signal
  }).then(async (res) => {
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const ct = (res.headers.get('content-type') || '').toLowerCase()
    if (ct.indexOf('text/event-stream') === -1) {
      const text = await res.text()
      let msg = '服务未返回流式结果'
      try { msg = errorMessage(JSON.parse(text), msg) } catch (e) { /* 原样兜底 */ }
      throw new Error(msg)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let sep
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        let event = '', data = ''
        frame.split('\n').forEach((line) => {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        })
        if (!event && !data) continue
        let obj = {}
        try { obj = JSON.parse(data) } catch (e) { /* 忽略坏帧 */ }
        // 后端帧统一带 type 字段，同时 SSE 的 event: 名也一致；两种都认，兼容解析差异
        const kind = event || obj.type
        if (kind === 'token' && obj.text) onToken && onToken(obj.text)
        else if (kind === 'node-start' && obj.node) onNode && onNode(obj.node)
        else if (kind === 'error') onError && onError(obj.message || obj.msg || '处理出错')
      }
    }
    onDone && onDone()
  }).catch((err) => {
    if (err.name === 'AbortError') return
    onError && onError(err.message || '连接失败')
  })

  return () => controller.abort()
}

/**
 * 上传 Excel：multipart/form-data，字段名 excel，可多文件。
 *
 * 成败只由统一 Result 的 code 决定：HTTP 200 且 code === '200' 才算成功。
 * data 仅作为展示文案使用，不做内容匹配，避免后端改文案导致误判。
 */
export function uploadFiles(files, onProgress) {
  const form = new FormData()
  files.forEach((f) => form.append('excel', f))
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', PREFIX + '/upload')
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round(e.loaded * 100 / e.total))
    }
    xhr.onload = () => {
      let payload
      try { payload = JSON.parse(xhr.responseText) } catch (e) { return reject(new Error('服务返回无法解析')) }
      if (xhr.status !== 200 || String(payload.code) !== '200') {
        return reject(new Error(errorMessage(payload, '上传失败（HTTP ' + xhr.status + '）')))
      }
      const message = typeof payload.data === 'string' && payload.data ? payload.data : '上传完成'
      resolve({ ok: true, message })
    }
    xhr.onerror = () => reject(new Error('网络连接失败'))
    xhr.send(form)
  })
}
