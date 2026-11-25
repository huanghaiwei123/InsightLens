<script setup>
import { ref, onMounted, provide } from 'vue'
import { useRoute } from 'vue-router'
import { fetchConfig } from '@/api'

const route = useRoute()
const tabs = [
  { path: '/', label: '概览', icon: '⌂' },
  { path: '/chat', label: '对话', icon: '✦' },
  { path: '/upload', label: '上传文件', icon: '↑' },
  { path: '/report', label: '数据报表', icon: '⌘' },
  { path: '/assets', label: '数据资产', icon: '▦' },
  { path: '/trace', label: '处理轨迹', icon: '↝' },
  { path: '/quality', label: '质量看板', icon: '◎' },
  { path: '/settings', label: '设置', icon: '⚙' }
]
const serviceOk = ref(false)
const workspaceRoot = ref('')

// 全局 toast：任意子组件 inject('toast') 后弹提示
const toastMsg = ref('')
let toastTimer = null
function toast(msg) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, 2600)
}
provide('toast', toast)
provide('workspaceRoot', workspaceRoot)

onMounted(() => {
  fetchConfig().then((d) => {
    workspaceRoot.value = d.workspaceRoot || ''
    serviceOk.value = true
  }).catch(() => { serviceOk.value = false })
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="mark">◈</span>
        <div>InsightLens<small>DATA Q&A</small></div>
      </div>
      <nav class="tabs">
        <router-link v-for="t in tabs" :key="t.path" :to="t.path"
                     class="tab" :class="{ active: route.path === t.path }">
          <span>{{ t.icon }}</span>{{ t.label }}
        </router-link>
      </nav>
      <div class="spacer"></div>
      <span class="status-pill">
        <i class="dot" :class="serviceOk ? 'ok' : 'err'"></i>
        {{ serviceOk ? '服务已连接' : '服务未连接' }}
      </span>
    </header>

    <main class="view">
      <router-view />
    </main>

    <div class="toast" :class="{ show: toastMsg }">{{ toastMsg }}</div>
  </div>
</template>
