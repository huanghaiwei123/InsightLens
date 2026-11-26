<script setup>
import { inject } from 'vue'
import { downloadExcel, openLocalExcel } from '@/api'

const props = defineProps({
  file: { type: Object, required: true },
  workspaceRoot: { type: String, default: '' },
  pathSegments: { type: Array, default: () => [] } // 从根到当前分类的名字，老数据兜底用
})
const toast = inject('toast')

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function openFile() {
  // file_path 是权威路径；老数据没写这个字段时，用分类层级 + 文件名兜底
  const relative = props.file.filePath || [...props.pathSegments, props.file.origName].join('/')
  const root = (props.workspaceRoot || '').replace(/\/+$/, '')
  const absPath = [root, String(relative).replace(/^\/+/, '')].filter(Boolean).join('/')

  const result = openLocalExcel(absPath)
  if (result === null) {
    // 浏览器没有本地文件权限，改走后端流式下载
    try {
      await downloadExcel(props.file.uploadId, props.file.origName)
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
  <button type="button" class="file-card" :title="'打开 ' + file.origName" @click="openFile">
    <div class="file-card-top">
      <span class="file-glyph">▤</span>
      <span class="file-kind">Excel</span>
      <span v-if="file.createdAt" class="file-time">{{ fmtTime(file.createdAt) }}</span>
    </div>
    <div class="file-name">{{ file.origName }}</div>
    <div class="file-sheets">
      <span v-for="s in file.sheets || []" :key="s.id" class="sheet-chip">
        {{ s.sheetName }} <b>{{ (s.rowCount || 0).toLocaleString() }}</b>
      </span>
      <span v-if="!(file.sheets || []).length" class="small muted">无工作表</span>
    </div>
    <div class="file-card-foot">{{ (file.sheets || []).length }} 张表 <span>打开 ↗</span></div>
  </button>
</template>
