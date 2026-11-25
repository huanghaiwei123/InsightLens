<script setup>
import { ref, inject, watch, onMounted } from 'vue'
import { fetchConfig } from '@/api'

const toast = inject('toast')
const root = ref('')
const showTrace = ref(localStorage.getItem('showTrace') !== '0')
const lessMotion = ref(localStorage.getItem('lessMotion') === '1')

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

onMounted(() => {
  applyMotion()
  document.body.classList.toggle('hide-trace', !showTrace.value)
  fetchConfig().then((d) => { root.value = d.workspaceRoot || '' }).catch(() => {})
})
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Settings</div>
      <h1>设置</h1>
      <p>这些设置保存在本机浏览器里，只影响你当前的显示方式，不会改动数据。</p>
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
    </div>
  </div>
</template>
