<script setup>
import { ref, onMounted, provide } from 'vue'
import { useRoute } from 'vue-router'
import { fetchConfig } from '@/api'
import Icon from '@/components/Icon.vue'

const route = useRoute()
const tabs = [
  { path: '/', label: '概览', icon: 'overview' },
  { path: '/chat', label: '对话', icon: 'chat' },
  { path: '/upload', label: '上传文件', icon: 'upload' },
  { path: '/report', label: '数据报表', icon: 'report' },
  { path: '/assets', label: '数据资产', icon: 'assets' },
  { path: '/trace', label: '处理轨迹', icon: 'report' },
  { path: '/quality', label: '质量看板', icon: 'quality' },
  { path: '/settings', label: '设置', icon: 'settings' }
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
    <header class="kb-topbar kb-scope">
      <div class="kb-brand">
        <span class="kb-brand-mark"><Icon name="logo" :size="16" /></span>
        <div>
          <div class="kb-brand-name">InsightLens</div>
          <span class="kb-brand-sub">Data Q&amp;A</span>
        </div>
      </div>
      <nav class="kb-nav" aria-label="主导航">
        <router-link v-for="t in tabs" :key="t.path" :to="t.path"
                     class="kb-nav-item" :class="{ active: route.path === t.path }">
          <Icon :name="t.icon" :size="14" />
          <span>{{ t.label }}</span>
        </router-link>
      </nav>
      <span class="kb-service">
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
