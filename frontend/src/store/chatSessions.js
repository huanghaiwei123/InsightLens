import { reactive } from 'vue'

/**
 * 分类 → 会话 的映射。每个大分类有自己独立的对话框，
 * 存在 localStorage 里，切页面、刷新窗口后都能回到原来的会话。
 */
const STORAGE_KEY = 'insightlens.chat.sessions'
const DEFAULT_KEY = '__default__'

function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch (e) {
    return {}
  }
}

const state = reactive({ byCategory: load() })

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.byCategory))
  } catch (e) { /* 隐私模式下写不进去也不影响本次会话 */ }
}

function keyOf(categoryId) {
  return categoryId == null || categoryId === '' ? DEFAULT_KEY : String(categoryId)
}

/** 取该分类已绑定的会话 id，没有返回 null */
export function sessionFor(categoryId) {
  return state.byCategory[keyOf(categoryId)] || null
}

/** 记住 分类 → 会话 的绑定 */
export function rememberSession(categoryId, sessionId) {
  state.byCategory[keyOf(categoryId)] = sessionId
  persist()
}

/** 解绑（清空对话时用），旧会话仍留在数据库，只是不再被界面引用 */
export function forgetSession(categoryId) {
  delete state.byCategory[keyOf(categoryId)]
  persist()
}
