<script setup>
import { ref, computed, inject, nextTick, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createSession, chatStream, fetchHistory } from '@/api'
import { sessionFor, rememberSession, forgetSession } from '@/store/chatSessions'

const toast = inject('toast')
const route = useRoute()
const router = useRouter()

const categoryId = computed(() => route.query.categoryId || null)
const categoryName = computed(() => route.query.categoryName || '通用对话')

const sessionId = ref(null)
const messages = ref([])   // { role: 'user'|'bot', text, trace: [] }
const input = ref('')
const streaming = ref(false)
const listEl = ref(null)
const historyLoading = ref(false)
let abort = null

const NODE_LABEL = {
  clarify: '理解问题', schema_retrieve: '查找可用数据', sql_generate: '生成查询',
  sql_guard: '安全检查', sql_execute: '取数', judge: '核对结果',
  summarize: '整理回答', react: '思考中'
}

/**
 * 每个大分类绑定一个独立会话。切换分类时按映射找回上次会话并回填历史，
 * 没有绑定过就留空，等首次提问时才创建，避免留下空会话。
 */
async function activateCategory() {
  if (streaming.value) stop()
  sessionId.value = sessionFor(categoryId.value)
  messages.value = []
  if (!sessionId.value) return
  historyLoading.value = true
  try {
    const rows = await fetchHistory(sessionId.value)
    messages.value = (rows || []).map((r) => ({
      role: r.role === 'user' ? 'user' : 'bot',
      text: r.text || '',
      trace: []
    }))
    scrollBottom()
  } catch (e) {
    toast('历史加载失败：' + e.message)
  } finally {
    historyLoading.value = false
  }
}

watch(categoryId, activateCategory)
onMounted(activateCategory)

async function ensureSession() {
  if (sessionId.value) return
  sessionId.value = await createSession(categoryId.value)
  rememberSession(categoryId.value, sessionId.value)
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', text, trace: [] })
  const botMsg = { role: 'bot', text: '', trace: [] }
  messages.value.push(botMsg)
  streaming.value = true
  scrollBottom()

  try {
    await ensureSession()
    abort = chatStream(text, sessionId.value, {
      onToken: (t) => {
        botMsg.text += t
        scrollBottom()
      },
      onNode: (node) => {
        const label = NODE_LABEL[node] || '处理中'
        if (botMsg.trace[botMsg.trace.length - 1] !== label) botMsg.trace.push(label)
      },
      onDone: () => { streaming.value = false },
      onError: (m) => {
        streaming.value = false
        if (!botMsg.text) botMsg.text = '本次没有生成回答：' + m
        toast(m)
      }
    })
  } catch (e) {
    streaming.value = false
    botMsg.text = '无法发起提问：' + e.message
  }
}

function stop() {
  if (abort) { abort(); abort = null }
  streaming.value = false
}

function clear() {
  if (streaming.value) stop()
  messages.value = []
  forgetSession(categoryId.value)
  sessionId.value = null
  toast('已清空当前分类的对话')
}

function backToCategories() {
  router.push('/report')
}

function scrollBottom() {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
}
</script>

<template>
  <div class="wrap chat-page">
    <div class="page-head">
      <button type="button" class="btn ghost chat-back" @click="backToCategories">← 返回分类</button>
      <div class="kicker">Chat · {{ categoryName }}</div>
      <h1>和「{{ categoryName }}」对话</h1>
      <p>这个分类有独立的对话与检索范围。切换分类后再回来，之前的提问和回答都还在。</p>
    </div>

    <div class="card">
      <div class="chat-layout">
        <div class="msg-list" ref="listEl">
          <div v-if="historyLoading" class="empty">正在恢复这段对话…</div>
          <div v-else-if="!messages.length" class="empty">
            <div class="big">✦</div>
            <div>还没有开始对话</div>
            <div class="small" style="margin-top:8px">试试问：「这个分类里有哪些数据？」</div>
          </div>
          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <div class="avatar">{{ m.role === 'user' ? '我' : '◈' }}</div>
            <div>
              <div class="bubble">{{ m.text || (streaming && m.role === 'bot' ? '正在思考…' : '') }}</div>
              <div v-if="m.trace.length" class="trace-line">经过：{{ m.trace.join(' → ') }}</div>
            </div>
          </div>
        </div>

        <div class="composer">
          <textarea v-model="input" placeholder="输入你的问题，回车发送，Shift+回车换行…"
                    @keydown.enter.exact.prevent="send" :disabled="streaming"></textarea>
          <button v-if="streaming" class="btn ghost" @click="stop">停止</button>
          <button v-else class="btn primary" @click="send" :disabled="!input.trim()">发送</button>
          <button class="btn ghost" @click="clear" title="清空当前分类的对话">清空</button>
        </div>
        <div class="small muted">单次回答最长等待约 2 分钟。历史保存在本机，清空后无法恢复。</div>
      </div>
    </div>
  </div>
</template>
