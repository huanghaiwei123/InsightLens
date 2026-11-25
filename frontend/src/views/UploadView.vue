<script setup>
import { ref, inject, onMounted, onUnmounted } from 'vue'
import { uploadFiles, fetchConfig } from '@/api'

const toast = inject('toast')
const files = ref([])
const dragging = ref(false)
const uploading = ref(false)
const percent = ref(0)
const result = ref(null)
const error = ref('')
const workspaceRoot = ref('')
const fileInput = ref(null)

const ALLOWED = ['.xlsx', '.xls']

onMounted(() => {
  fetchConfig().then((d) => { workspaceRoot.value = d.workspaceRoot || '' }).catch(() => {})
})

function pick() { fileInput.value && fileInput.value.click() }

function addFiles(list) {
  for (const f of list) {
    if (!ALLOWED.some((ext) => f.name.toLowerCase().endsWith(ext))) {
      toast('只支持 Excel 文件（.xlsx / .xls）')
      continue
    }
    if (!files.value.some((x) => x.name === f.name && x.size === f.size)) files.value.push(f)
  }
}

function onDrop(e) {
  dragging.value = false
  addFiles(e.dataTransfer.files)
}
function onChoose(e) {
  addFiles(e.target.files)
  e.target.value = ''
}
function remove(f) {
  files.value = files.value.filter((x) => x !== f)
}
function fmtSize(n) {
  return n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : Math.round(n / 1024) + ' KB'
}

async function submit() {
  if (!files.value.length || uploading.value) return
  uploading.value = true
  error.value = ''
  result.value = null
  percent.value = 0
  try {
    const r = await uploadFiles(files.value, (p) => { percent.value = p })
    if (!r.ok) {
      error.value = r.message
      toast('上传失败：' + r.message)
      return
    }
    result.value = r
    files.value = []
    toast('上传完成')
  } catch (e) {
    error.value = e.message
    toast('上传失败：' + e.message)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Upload</div>
      <h1>上传 Excel 文件</h1>
      <p>选好文件后，系统会读取每个工作表、判断业务类别并自动归档。上传完就能在「对话」里直接提问，在「数据报表」里找到它。</p>
    </div>

    <div class="grid">
      <div class="card sp-8">
        <div class="dropzone" :class="{ dragging }"
             @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop"
             @click="pick">
          <div class="ico">⇪</div>
          <h2 style="margin-top:10px">把 Excel 拖到这里，或点击选择</h2>
          <p class="desc">支持 .xlsx / .xls，可一次选多个文件，单个文件不超过 20 MB</p>
        </div>
        <input ref="fileInput" type="file" multiple accept=".xlsx,.xls" style="display:none" @change="onChoose" />

        <div v-if="files.length" style="margin-top:18px">
          <div v-for="f in files" :key="f.name + f.size" class="file-row">
            <span>▤</span>
            <span class="name">{{ f.name }}</span>
            <span class="size">{{ fmtSize(f.size) }}</span>
            <button class="file-remove" @click="remove(f)" title="移除">×</button>
          </div>
          <div v-if="uploading" class="progress"><i :style="{ width: percent + '%' }"></i></div>
          <div style="margin-top:16px; display:flex; gap:10px">
            <button class="btn primary" :disabled="uploading" @click="submit">
              {{ uploading ? '上传中 ' + percent + '%' : '开始上传（' + files.length + '）' }}
            </button>
          </div>
        </div>

        <div v-if="error" class="alert err" style="margin-top:16px">{{ error }}</div>

        <div v-if="result" class="alert ok" style="margin-top:16px">
          <b>上传完成</b>：{{ result.message }}
          <div class="small" style="margin-top:8px">文件已按业务归类，可到「数据报表」查看分类与工作表清单。</div>
        </div>
      </div>

      <div class="card sp-4">
        <h2>文件放在哪里</h2>
        <p class="desc">上传的文件会按业务归类到下面的目录，原始文件始终保留，随时可以定位打开。</p>
        <div class="mono small" style="margin-top:14px; word-break:break-all; background:rgba(44,39,35,.05); padding:12px; border-radius:12px">
          {{ workspaceRoot || '正在读取文件目录…' }}
        </div>
        <p class="desc" style="margin-top:14px">
          系统会自动给文件加上唯一后缀再归类，同名文件也不会互相覆盖。上传后到「数据报表」即可查看分类结果。
        </p>
      </div>
    </div>
  </div>
</template>
