// 后端地址：Web 形态为空（相对路径，同源），Electron 形态由 VITE_API_BASE 注入。
const BASE = typeof __API_BASE__ !== 'undefined' ? __API_BASE__ : ''
const PREFIX = BASE + '/personal'

/** 统一 Result：{ code, msg, data }。code 非 "200" 视为业务失败。 */
async function unwrap(res) {
  let payload
  try {
    payload = await res.json()
  } catch (e) {
    throw new Error('服务返回的内容无法解析（HTTP ' + res.status + '）')
  }
  if (!res.ok || String(payload.code) !== '200') {
    throw new Error(payload.msg || ('请求失败（HTTP ' + res.status + '）'))
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
      try { msg = JSON.parse(text).msg || msg } catch (e) { /* 原样兜底 */ }
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
        return reject(new Error(payload.msg || '上传失败（HTTP ' + xhr.status + '）'))
      }
      const message = typeof payload.data === 'string' && payload.data ? payload.data : '上传完成'
      resolve({ ok: true, message })
    }
    xhr.onerror = () => reject(new Error('网络连接失败'))
    xhr.send(form)
  })
}
