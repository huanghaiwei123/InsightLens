<script setup>
import { ref } from 'vue'
const props = defineProps({ node: { type: Object, required: true }, level: { type: Number, default: 0 } })
const open = ref(true)
function key(n) { return n.id ?? n.name }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
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
      <div v-for="f in node.files || []" :key="f.uploadId || f.origName" class="tree-file">
        <span>▤</span>
        <span class="name" style="flex:1">{{ f.origName }}</span>
        <span v-for="s in f.sheets || []" :key="s.id" class="tag gold">{{ s.sheetName }} · {{ s.rowCount }} 行</span>
        <span v-if="f.uploadId" class="path">{{ f.uploadId }}</span>
      </div>
      <TreeNode v-for="c in node.children || []" :key="key(c)" :node="c" :level="level + 1" />
    </div>
  </div>
</template>
