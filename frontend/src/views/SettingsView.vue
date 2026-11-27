<script setup>
import { computed, ref, inject, watch, onMounted } from 'vue'
import { fetchConfig, fetchLlmSettings, switchLlmProvider, saveLlmKey } from '@/api'

const toast = inject('toast')
const root = ref('')
const showTrace = ref(localStorage.getItem('showTrace') !== '0')
const lessMotion = ref(localStorage.getItem('lessMotion') === '1')

// 模型配置：一次拿全两家，切主模型 / 改 Key 都返回同一份结构，前端直接覆盖。
const llm = ref(null)
const llmLoading = ref(false)
const switching = ref('')
const saving = ref('')
const draftKeys = ref({})
const reveal = ref({})

const providers = computed(() => (llm.value && llm.value.providers) || [])
const activeLabel = computed(() => {
  const hit = providers.value.find((p) => p.active)
  return hit ? hit.label : '—'
})

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

function loadLlm() {
  llmLoading.value = true
  return fetchLlmSettings()
    .then((d) => { llm.value = d })
    .catch((e) => toast('读取模型配置失败：' + e.message))
    .finally(() => { llmLoading.value = false })
}

// 保存某一家（OpenAI / DeepSeek）的 Key：后端落盘并重建该家实例，若是主模型则下一轮立即生效。
async function applyKey(p) {
  const value = (draftKeys.value[p.id] || '').trim()
  if (!value) { toast('请先填入新的 API Key'); return }
  saving.value = p.id
  try {
    llm.value = await saveLlmKey(p.id, value)
    draftKeys.value[p.id] = ''
    reveal.value[p.id] = false
    toast(p.label + ' 的 API Key 已更新，立即生效')
  } catch (e) {
    toast('保存失败：' + e.message)
  } finally {
    saving.value = ''
  }
}

// 切换主模型：全局生效，所有会话下一轮都走它；选择会落盘，重启后仍是这家。
async function applyProvider(p) {
  switching.value = p.id
  try {
    llm.value = await switchLlmProvider(p.id)
    toast('已切换到 ' + p.label + '，下一轮提问立即生效')
  } catch (e) {
    toast('切换失败：' + e.message)
  } finally {
    switching.value = ''
  }
}

onMounted(() => {
  applyMotion()
  document.body.classList.toggle('hide-trace', !showTrace.value)
  fetchConfig().then((d) => { root.value = d.workspaceRoot || '' }).catch(() => {})
  loadLlm()
})
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Settings</div>
      <h1>设置</h1>
      <p>这些设置保存在本机，只影响你当前的显示方式和模型配置，不会改动数据。</p>
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
            <h2>主模型</h2>
            <p class="desc">选择用哪家大模型回答问题。切换后全局生效，无需重启，选择会保存在本机。</p>
          </div>
          <span class="tag sage">当前：{{ llmLoading ? '读取中' : activeLabel }}</span>
        </div>

        <div class="providers">
          <div v-for="p in providers" :key="p.id" class="provider" :class="{ active: p.active }">
            <div class="provider-head">
              <div>
                <b>{{ p.label }}</b>
                <div class="desc mono">{{ p.model }}</div>
              </div>
              <span class="tag" :class="p.active ? 'sage' : (p.configured ? 'muted' : 'gold')">
                {{ p.active ? '当前使用' : (p.configured ? '已配置' : '未配置') }}
              </span>
            </div>

            <div class="provider-meta mono small">
              <span>{{ p.baseUrl || '接口地址未设置' }}</span>
              <span>密钥：{{ p.masked }}</span>
            </div>

            <div class="key-row">
              <input
                v-model="draftKeys[p.id]"
                :type="reveal[p.id] ? 'text' : 'password'"
                class="key-input mono"
                :placeholder="p.configured ? '粘贴新的 API Key（留空不改）' : '粘贴 API Key'"
                autocomplete="off"
                spellcheck="false"
                @keyup.enter="applyKey(p)"
              />
              <button class="btn ghost" type="button" @click="reveal[p.id] = !reveal[p.id]">
                {{ reveal[p.id] ? '隐藏' : '显示' }}
              </button>
              <button class="btn ghost" type="button" :disabled="saving === p.id" @click="applyKey(p)">
                {{ saving === p.id ? '保存中…' : '保存' }}
              </button>
            </div>

            <button
              class="btn primary provider-switch"
              type="button"
              :disabled="p.active || !p.configured || switching === p.id"
              @click="applyProvider(p)"
            >
              {{ p.active ? '当前主模型' : (p.configured ? '设为主模型' : '请先配置 Key') }}
            </button>
          </div>
        </div>

        <p class="desc key-note">
          密钥只写入本机的 application-local.yaml，页面只显示头尾各 4 位；两家可以都填，随时切换。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.key-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.providers {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px; margin-top: 18px;
}
.provider {
  display: flex; flex-direction: column; gap: 12px;
  padding: 16px; border: 1px solid var(--line); border-radius: var(--r-md);
  background: rgba(255,255,255,.55);
}
.provider.active { border-color: rgba(111,154,126,.55); background: var(--sage-soft); }
.provider-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.provider-meta { display: flex; flex-direction: column; gap: 2px; color: var(--ink-3); word-break: break-all; }
.provider-switch { width: 100%; }
.key-row { display: flex; gap: 8px; align-items: center; }
.key-input {
  flex: 1; min-width: 0; padding: 10px 12px; border-radius: var(--r-md);
  border: 1px solid var(--line); background: rgba(255,255,255,.85);
  outline: none; font-size: 13px;
}
.key-input:focus { border-color: rgba(217,122,69,.5); }
.key-note { margin-top: 14px; }
@media (max-width: 720px) {
  .providers { grid-template-columns: 1fr; }
  .key-row { flex-wrap: wrap; }
  .key-input { flex-basis: 100%; }
}
</style>
