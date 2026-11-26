<script setup>
import { ref, inject, watch, onMounted } from 'vue'
import { fetchConfig, fetchApiKeySettings, saveApiKey } from '@/api'

const toast = inject('toast')
const root = ref('')
const showTrace = ref(localStorage.getItem('showTrace') !== '0')
const lessMotion = ref(localStorage.getItem('lessMotion') === '1')

// API Key 明文只在用户输入框里存在；页面读到的是后端返回的掩码串。
const apiKey = ref('')
const apiKeyInfo = ref(null)
const apiKeyLoading = ref(false)
const savingKey = ref(false)
const revealKey = ref(false)

function applyMotion() {
  document.body.classList.toggle('less-motion', lessMotion.value)
}
watch(showTrace, (v) => {
  localStorage.setItem('showTrace', v ? '1' : '0')
  document.body.classList.toggle('hide-trace', !v)
  toast(v ? '已显示处理步骤' : '已隐藏处理步骤')
})
watch(lessMotion, (v) => {
  localStorage.setItem('lessMotion', v ? '1' : '0')
  applyMotion()
  toast(v ? '已缩短动画' : '已恢复动画')
})

function loadApiKey() {
  apiKeyLoading.value = true
  return fetchApiKeySettings()
    .then((d) => { apiKeyInfo.value = d })
    .catch((e) => toast('读取模型配置失败：' + e.message))
    .finally(() => { apiKeyLoading.value = false })
}

async function applyApiKey() {
  const value = apiKey.value.trim()
  if (!value) { toast('请先填入新的 API Key'); return }
  savingKey.value = true
  try {
    apiKeyInfo.value = await saveApiKey(value)
    apiKey.value = ''
    revealKey.value = false
    toast('API Key 已更新，立即生效')
  } catch (e) {
    toast('保存失败：' + e.message)
  } finally {
    savingKey.value = false
  }
}

onMounted(() => {
  applyMotion()
  document.body.classList.toggle('hide-trace', !showTrace.value)
  fetchConfig().then((d) => { root.value = d.workspaceRoot || '' }).catch(() => {})
  loadApiKey()
})
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Settings</div>
      <h1>设置</h1>
      <p>这些设置保存在本机，只影响你当前的显示方式和模型密钥，不会改动数据。</p>
    </div>
    <div class="grid">
      <div class="card sp-8">
        <h2>显示偏好</h2>
        <div class="set-row">
          <div><b>显示处理步骤</b><div class="desc">在每条回答下方显示本轮经过的环节。</div></div>
          <button class="switch" :class="{ on: showTrace }" @click="showTrace = !showTrace"></button>
        </div>
        <div class="set-row">
          <div><b>缩短动画</b><div class="desc">减少页面过渡效果，适合低性能设备。</div></div>
          <button class="switch" :class="{ on: lessMotion }" @click="lessMotion = !lessMotion"></button>
        </div>
      </div>
      <div class="card sp-4">
        <h2>文件目录</h2>
        <p class="desc">上传的文件归档在这个目录下：</p>
        <div class="mono small" style="margin-top:12px; word-break:break-all; background:rgba(44,39,35,.05); padding:12px; border-radius:12px">
          {{ root || '正在读取…' }}
        </div>
      </div>

      <div class="card sp-12">
        <div class="key-head">
          <div>
            <h2>模型 API Key</h2>
            <p class="desc">用于调用大模型。修改后立即生效，无需重启应用。</p>
          </div>
          <span class="tag" :class="apiKeyInfo && apiKeyInfo.configured ? 'sage' : 'gold'">
            {{ apiKeyLoading ? '读取中' : (apiKeyInfo && apiKeyInfo.configured ? '已配置' : '未配置') }}
          </span>
        </div>

        <div class="key-current">
          <span class="key-label">当前密钥</span>
          <span class="mono">{{ apiKeyInfo ? apiKeyInfo.masked : '—' }}</span>
          <span v-if="apiKeyInfo && apiKeyInfo.baseUrl" class="key-url mono small">
            接口地址：{{ apiKeyInfo.baseUrl }}
          </span>
        </div>

        <div class="key-form">
          <input
            v-model="apiKey"
            :type="revealKey ? 'text' : 'password'"
            class="key-input mono"
            placeholder="粘贴新的 API Key"
            autocomplete="off"
            spellcheck="false"
            @keyup.enter="applyApiKey"
          />
          <button class="btn ghost" type="button" @click="revealKey = !revealKey">
            {{ revealKey ? '隐藏' : '显示' }}
          </button>
          <button class="btn primary" type="button" :disabled="savingKey" @click="applyApiKey">
            {{ savingKey ? '保存中…' : '保存并生效' }}
          </button>
        </div>
        <p class="desc key-note">
          明文只写入本机的 application-local.yaml，页面不会再次显示完整密钥；留空不修改。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.key-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.key-current {
  display: flex; align-items: center; flex-wrap: wrap; gap: 10px;
  margin: 18px 0 14px; padding: 12px 14px; border-radius: var(--r-md);
  background: rgba(44,39,35,.05); font-size: 13px;
}
.key-label { color: var(--ink-2); }
.key-url { margin-left: auto; color: var(--ink-3); }
.key-form { display: flex; gap: 10px; align-items: center; }
.key-input {
  flex: 1; min-width: 0; padding: 11px 14px; border-radius: var(--r-md);
  border: 1px solid var(--line); background: rgba(255,255,255,.85);
  outline: none; font-size: 13px;
}
.key-input:focus { border-color: rgba(217,122,69,.5); }
.key-note { margin-top: 12px; }
@media (max-width: 720px) {
  .key-form { flex-wrap: wrap; }
  .key-input { flex-basis: 100%; }
  .key-url { margin-left: 0; }
}
</style>
