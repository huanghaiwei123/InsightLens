<script setup>
import { computed, inject, ref } from 'vue'
import { downloadExcel, openLocalExcel } from '@/api'

const props = defineProps({
  node: { type: Object, required: true },
  level: { type: Number, default: 0 },
  workspaceRoot: { type: String, default: '' },
  parentPath: { type: Array, default: () => [] }
})
const toast = inject('toast')
const open = ref(true)
const segments = computed(() => [...props.parentPath, props.node.name])

function key(n) { return n.id ?? n.name }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function openFile(file) {
  // file_path 是权威路径；老数据没写这个字段时，用树上的分类层级 + 文件名兜底
  const relative = file.filePath || [...segments.value, file.origName].join('/')
  const root = (props.workspaceRoot || '').replace(/\/+$/, '')
  const absPath = [root, String(relative).replace(/^\/+/, '')].filter(Boolean).join('/')

  const result = openLocalExcel(absPath)
  if (result === null) {
    // 浏览器没有本地文件权限，改走后端流式下载；失败时用 toast 显示业务原因
    try {
      await downloadExcel(file.uploadId, file.origName)
    } catch (e) {
      toast('打开失败：' + e.message)
    }
    return
  }
  try {
    const error = await result
    if (error) toast('打开失败：' + error)
  } catch (e) {
    toast('打开失败：' + e.message)
  }
}
</script>

<template>
  <div class="tree-node">
    <div class="tree-title" @click="open = !open" style="cursor:pointer">
      <span>{{ open ? '▾' : '▸' }}</span>
      <span>{{ node.name }}</span>
      <span class="tag sage">{{ node.fileCount }} 文件</span>
      <span class="tag muted">{{ node.sheetCount }} 表 · {{ (node.totalRows || 0).toLocaleString() }} 行</span>
      <span v-if="node.createdAt" class="small muted" style="margin-left:auto">{{ fmtTime(node.createdAt) }}</span>
    </div>
    <div v-if="open">
      <button v-for="f in node.files || []" :key="f.uploadId || f.origName" type="button"
              class="tree-file file-btn" :title="'打开 ' + f.origName" @click="openFile(f)">
        <span>▤</span>
        <span class="name" style="flex:1">{{ f.origName }}</span>
        <span v-for="s in f.sheets || []" :key="s.id" class="tag gold">{{ s.sheetName }} · {{ s.rowCount }} 行</span>
        <span v-if="f.uploadId" class="path">{{ f.uploadId }}</span>
        <span class="tag sage">打开 ↗</span>
      </button>
      <TreeNode v-for="c in node.children || []" :key="key(c)" :node="c" :level="level + 1"
                :workspace-root="workspaceRoot" :parent-path="segments" />
    </div>
  </div>
</template>
