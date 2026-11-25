<script setup>
import { ref, inject, nextTick, onMounted } from 'vue'
import { createSession, chatStream } from '@/api'

const toast = inject('toast')
const sessionId = ref(null)
const messages = ref([])   // { role: 'user'|'bot', text, trace: [] }
const input = ref('')
const streaming = ref(false)
const listEl = ref(null)
let abort = null

const NODE_LABEL = {
  clarify: '理解问题', schema_retrieve: '查找可用数据', sql_generate: '生成查询',
  sql_guard: '安全检查', sql_execute: '取数', judge: '核对结果',
  summarize: '整理回答', react: '思考中'
}

async function ensureSession() {
  if (sessionId.value) return
  sessionId.value = await createSession()
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
  sessionId.value = null
  toast('已清空当前对话')
}

function scrollBottom() {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
}

onMounted(() => { /* 会话首次提问时再创建，避免留下空窗口 */ })
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Chat</div>
      <h1>直接问你的数据</h1>
      <p>用日常语言描述你想知道的事。回答会一段段显示出来，显示完就可以接着追问。</p>
    </div>

    <div class="card">
      <div class="chat-layout">
        <div class="msg-list" ref="listEl">
          <div v-if="!messages.length" class="empty">
            <div class="big">✦</div>
            <div>还没有开始对话</div>
            <div class="small" style="margin-top:8px">试试问：「各分类的数据有多少行？」</div>
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
          <button class="btn ghost" @click="clear" title="清空当前对话">清空</button>
        </div>
        <div class="small muted">单次回答最长等待约 2 分钟。历史不会长期保存，清空后无法恢复。</div>
      </div>
    </div>
  </div>
</template>
