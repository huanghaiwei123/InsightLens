<script setup>
import { ref, inject, onMounted } from 'vue'
import { fetchConfig, fetchReport } from '@/api'
import TreeNode from '@/components/TreeNode.vue'

const toast = inject('toast')
const tree = ref([])
const workspaceRoot = ref('')
const loading = ref(true)

onMounted(() => {
  Promise.all([fetchReport(), fetchConfig()]).then(([d, config]) => {
    tree.value = d || []
    workspaceRoot.value = config?.workspaceRoot || ''
    loading.value = false
  }).catch((e) => {
    loading.value = false
    toast('读取报表失败：' + e.message)
  })
})
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Reports</div>
      <h1>数据报表</h1>
      <p>按分类浏览已登记的数据资产。分类下可以有子目录；点击文件名可在桌面端用 Excel 打开，浏览器里则直接下载。</p>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">正在读取分类…</div>
      <div v-else-if="!tree.length" class="empty">
        <div class="big">▦</div>
        <div>还没有登记任何数据</div>
        <div class="small" style="margin-top:8px">先到「上传文件」放一份 Excel。</div>
      </div>
      <div v-else>
        <TreeNode v-for="n in tree" :key="n.id ?? n.name" :node="n" :workspace-root="workspaceRoot" />
      </div>
    </div>
  </div>
</template>
