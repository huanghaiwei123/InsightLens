<script setup>
import { ref, inject, onMounted, computed } from 'vue'
import { fetchConfig, fetchReport } from '@/api'
import CategoryCard from '@/components/CategoryCard.vue'
import FileCard from '@/components/FileCard.vue'

const toast = inject('toast')
const tree = ref([])
const workspaceRoot = ref('')
const loading = ref(true)
// 下钻栈：每项 { node, segments }，segments 是从根到该节点的名字
const trail = ref([])

const current = computed(() => trail.value.length
  ? trail.value[trail.value.length - 1].node
  : null)
const currentSegments = computed(() => trail.value.length
  ? trail.value[trail.value.length - 1].segments
  : [])
const children = computed(() => current.value ? (current.value.children || []) : (tree.value || []))
const files = computed(() => current.value?.files || [])

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

function drill(node) {
  const segments = [...currentSegments.value, node.name]
  trail.value.push({ node, segments })
}

function backTo(index) {
  trail.value.splice(index)
}
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Reports</div>
      <h1>数据报表</h1>
      <p>点开分类逐层深入，查看子分类与文件。点击文件在桌面端用 Excel 打开，浏览器里则直接下载。</p>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">正在读取分类…</div>
      <div v-else-if="!tree.length" class="empty">
        <div class="big">▦</div>
        <div>还没有登记任何数据</div>
        <div class="small" style="margin-top:8px">先到「上传文件」放一份 Excel。</div>
      </div>
      <template v-else>
        <nav class="crumb-bar">
          <button type="button" class="crumb" :class="{ here: !trail.length }" @click="backTo(0)">全部数据</button>
          <template v-for="(step, i) in trail" :key="i">
            <span class="crumb-sep">/</span>
            <button type="button" class="crumb" :class="{ here: i === trail.length - 1 }" @click="backTo(i + 1)">
              {{ step.node.name }}
            </button>
          </template>
          <span class="crumb-count">{{ children.length }} 个分类 · {{ files.length }} 个文件</span>
        </nav>

        <div v-if="!children.length && !files.length" class="empty">
          <div class="big">∅</div>
          <div>这一层是空的</div>
        </div>
        <template v-else>
          <div v-if="children.length" class="card-grid">
            <CategoryCard v-for="n in children" :key="n.id ?? n.name" :node="n" @drill="drill" />
          </div>
          <div v-if="files.length" class="card-grid">
            <FileCard v-for="f in files" :key="f.uploadId || f.origName" :file="f"
                      :workspace-root="workspaceRoot" :path-segments="currentSegments" />
          </div>
        </template>
      </template>
    </div>
  </div>
</template>
