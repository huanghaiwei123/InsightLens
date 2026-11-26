<script setup>
import { ref, computed, onMounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { fetchConfig, fetchReport, downloadExcel, openLocalExcel } from '@/api'
import Icon from '@/components/Icon.vue'
import FilterPanel from '@/components/FilterPanel.vue'
import CategoryPills from '@/components/CategoryPills.vue'

/**
 * 数据报表 / 知识图谱仪表板（全屏悬浮画布）
 * - 分类完全来自后端 /personal/report/categories：大分类 = category.name（中文），
 *   小分类 = file_path 的目录层级；前端不预设任何业务分类，任何数据上传后都能显示。
 * - 筛选维度是与数据内容无关的客观属性（表数/行数/状态/时间），不绑定某个业务域。
 */
const toast = inject('toast', () => {})
const router = useRouter()
const loading = ref(true)
const workspaceRoot = ref('')
const tree = ref([])              // 后端原始分类树
const selected = ref(new Set())
const activePill = ref('all')
const zoom = ref(1)

const dot = (n) => `var(--dot-${n})`

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(11, 16) : '' }
function fmtRows(n) {
  if (!n) return '0'
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}
function timeBucket(t) {
  if (!t) return 'old'
  const days = (Date.now() - new Date(String(t).replace(' ', 'T')).getTime()) / 86400000
  if (days <= 7) return '7d'
  if (days <= 30) return '30d'
  return 'old'
}

/* ---------- 递归收集文件：带上所属大分类 id 与目录层级 ---------- */
function collectFiles(nodes, categoryId, path = []) {
  const out = []
  for (const n of nodes || []) {
    const seg = [...path, n.name]
    for (const f of n.files || []) out.push({ file: f, categoryId, segments: seg })
    out.push(...collectFiles(n.children || [], categoryId, seg))
  }
  return out
}
const allEntries = computed(() => tree.value.flatMap((n) => collectFiles([n], String(n.id))))

/* 文件的客观属性 → 筛选 facets（与数据内容无关，任何上传都适用） */
function facetsOf(file) {
  const sheets = file.sheets || []
  const rows = sheets.reduce((s, x) => s + (x.rowCount || 0), 0)
  return [
    sheets.length <= 1 ? 'single' : 'multi',
    rows < 10000 ? 'small' : (rows < 100000 ? 'medium' : 'large'),
    sheets.every((s) => s.status === 'success') ? 'ok' : 'warn',
    timeBucket(file.createdAt)
  ]
}

/* ---------- 组件A：4 组通用筛选维度（不绑定业务域） ---------- */
const filterDefs = [
  {
    title: '文件规模',
    options: [
      { id: 'single', label: '单张表', dot: dot(1) },
      { id: 'multi', label: '多张表', dot: dot(2) }
    ]
  },
  {
    title: '数据行数',
    options: [
      { id: 'small', label: '1 万行以下', dot: dot(3) },
      { id: 'medium', label: '1-10 万行', dot: dot(4) },
      { id: 'large', label: '10 万行以上', dot: dot(5) }
    ]
  },
  {
    title: '表状态',
    options: [
      { id: 'ok', label: '全部成功', dot: dot(4) },
      { id: 'warn', label: '含待校验', dot: dot(2) }
    ]
  },
  {
    title: '上传时间',
    options: [
      { id: '7d', label: '近 7 天', dot: dot(1) },
      { id: '30d', label: '近 30 天', dot: dot(3) },
      { id: 'old', label: '更早', dot: dot(6) }
    ]
  }
]
const groups = computed(() => filterDefs.map((g) => ({
  title: g.title,
  options: g.options.map((o) => ({
    ...o,
    count: allEntries.value.filter((e) => facetsOf(e.file).includes(o.id)).length
  }))
})))

/* ---------- 组件B：胶囊条直接来自后端大分类的中文名 ---------- */
const pills = computed(() => [
  { id: 'all', label: '全部数据', dot: dot(1) },
  ...tree.value.map((n, i) => ({ id: String(n.id), label: n.name, dot: dot((i % 7) + 1) }))
])
const currentPill = computed(() => pills.value.find((p) => p.id === activePill.value) || pills.value[0])

onMounted(() => {
  Promise.all([fetchReport().catch(() => []), fetchConfig().catch(() => null)])
    .then(([data, config]) => {
      workspaceRoot.value = config?.workspaceRoot || ''
      tree.value = Array.isArray(data) ? data : []
      loading.value = false
    })
    .catch(() => { tree.value = []; loading.value = false })
})

/* ---------- 过滤：组内 OR，组间 AND，再叠加胶囊 ---------- */
const visibleEntries = computed(() => allEntries.value.filter((e) => {
  if (activePill.value !== 'all' && e.categoryId !== activePill.value) return false
  const facets = facetsOf(e.file)
  for (const g of filterDefs) {
    const picked = g.options.filter((o) => selected.value.has(o.id)).map((o) => o.id)
    if (picked.length && !picked.some((id) => facets.includes(id))) return false
  }
  return true
}))
const categoryCount = computed(() => new Set(visibleEntries.value.map((e) => e.categoryId)).size)
function resetFilters() { selected.value = new Set(); activePill.value = 'all' }

/* ---------- 画布节点：大分类节点 + 文件节点 ---------- */
const VW = 1200
const VH = 720
const NODE_W = 240
const LAYER_H = 118
const FILE_H = 152

const layerSlots = [
  { x: 96, y: 258 }, { x: 480, y: 224 }, { x: 880, y: 262 }, { x: 300, y: 520 }
]
const fileSlots = [
  { x: 108, y: 424 }, { x: 492, y: 462 }, { x: 884, y: 424 },
  { x: 280, y: 566 }, { x: 640, y: 566 }, { x: 940, y: 566 }
]

const layerNodes = computed(() => tree.value.slice(0, layerSlots.length).map((n, i) => {
  const slot = layerSlots[i % layerSlots.length]
  return {
    id: 'layer-' + n.id, kind: 'layer', categoryId: String(n.id),
    label: n.name,
    desc: `${n.fileCount} 个文件 · ${fmtRows(n.totalRows)} 行数据`,
    dot: dot((i % 7) + 1),
    count: n.fileCount,
    level: n.fileCount <= 2 ? 'simple' : 'moderate',
    x: slot.x, y: slot.y, delay: -(i * 1.3)
  }
}))

const fileNodes = computed(() => visibleEntries.value.slice(0, fileSlots.length).map((e, i) => {
  const f = e.file
  const sheets = f.sheets || []
  const rows = sheets.reduce((s, x) => s + (x.rowCount || 0), 0)
  const slot = fileSlots[i % fileSlots.length]
  return {
    ...e, kind: 'file', id: f.uploadId,
    title: f.origName || '未命名文件',
    tags: [`${sheets.length} 张表`, `${fmtRows(rows)} 行`, fmtTime(f.createdAt)].filter(Boolean),
    tableCount: sheets.length,
    time: fmtTime(f.createdAt),
    real: f,
    x: slot.x, y: slot.y, delay: -(i * 0.9 + 0.4)
  }
}))

/* 连线：文件节点 → 所属大分类节点 */
const edges = computed(() => {
  const out = []
  for (const f of fileNodes.value) {
    const l = layerNodes.value.find((n) => n.categoryId === f.categoryId)
    if (!l) continue
    const x1 = l.x + NODE_W
    const y1 = l.y + LAYER_H / 2
    const x2 = f.x
    const y2 = f.y + FILE_H / 2
    const mx = (x1 + x2) / 2
    out.push({
      id: l.id + '-' + f.id,
      d: `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
    })
  }
  return out
})

function nodeStyle(n) {
  return {
    left: (n.x / VW * 100) + '%',
    top: (n.y / VH * 100) + '%',
    '--float-delay': n.delay + 's'
  }
}
function zoomBy(delta) {
  zoom.value = Math.min(1.4, Math.max(0.6, Math.round((zoom.value + delta) * 10) / 10))
}

/* ---------- 打开文件 / 点击大分类筛选 ---------- */
async function openFile(card) {
  const relative = card.real.filePath || [...card.segments, card.real.origName].join('/')
  const root = (workspaceRoot.value || '').replace(/\/+$/, '')
  const absPath = [root, String(relative).replace(/^\/+/, '')].filter(Boolean).join('/')
  const result = openLocalExcel(absPath)
  if (result === null) {
    try { await downloadExcel(card.real.uploadId, card.real.origName) }
    catch (e) { toast('打开失败：' + e.message) }
    return
  }
  try { const err = await result; if (err) toast('打开失败：' + err) }
  catch (e) { toast('打开失败：' + e.message) }
}
function pickLayer(n) {
  activePill.value = activePill.value === n.categoryId ? 'all' : n.categoryId
}

/** 点「进入对话」：带着分类 id 与中文名跳转，ChatView 按 id 找回专属会话 */
function chatWithCategory(n) {
  router.push({ path: '/chat', query: { categoryId: n.categoryId, categoryName: n.label } })
}
</script>

<template>
  <div class="kb-scope kb-page kb-graph-page">
    <div class="kb-canvas">
      <!-- 悬浮控制条 -->
      <div class="kb-graph-bar">
        <div class="kb-graph-bar-row">
          <div class="kb-graph-title">
            <span class="kb-eyebrow">Reports</span>
            <h1>数据报表</h1>
          </div>
          <nav class="kb-crumb" aria-label="面包屑">
            <button type="button" class="kb-crumb-item"
                    :class="{ here: activePill === 'all' }" @click="activePill = 'all'">全部数据</button>
            <template v-if="activePill !== 'all'">
              <span class="kb-crumb-sep">/</span>
              <button type="button" class="kb-crumb-item here">{{ currentPill.label }}</button>
            </template>
          </nav>
          <FilterPanel v-model="selected" :groups="groups" />
          <span class="kb-count">{{ categoryCount }} 个分类 · {{ visibleEntries.length }} 个文件</span>
        </div>
        <CategoryPills :pills="pills" :active-id="activePill" @select="activePill = $event" />
      </div>

      <!-- 悬浮画布世界 -->
      <div class="kb-world" :style="{ transform: 'scale(' + zoom + ')' }">
        <svg class="kb-edges" :viewBox="`0 0 ${VW} ${VH}`" preserveAspectRatio="none" aria-hidden="true">
          <path v-for="e in edges" :key="e.id" :d="e.d" class="kb-edge" />
        </svg>

        <div v-if="loading" class="kb-empty">正在读取数据…</div>
        <div v-else-if="!visibleEntries.length" class="kb-empty">
          <div class="big">∅</div>
          <div>还没有数据，先去上传文件吧</div>
          <div class="small"><button type="button" class="kb-card-open" @click="resetFilters">清除筛选</button></div>
        </div>
        <template v-else>
          <!-- 大分类节点：名称来自后端 category.name；点「进入对话」打开该分类专属会话 -->
          <article v-for="n in layerNodes" :key="n.id"
                   class="kb-node kb-layer" :class="{ 'is-active': activePill === n.categoryId }"
                   :style="nodeStyle(n)" @click="pickLayer(n)">
            <span class="kb-layer-stripe" :style="{ background: n.dot }"></span>
            <span class="kb-layer-top">
              <span class="kb-node-eyebrow">Layer</span>
              <span class="kb-layer-level">{{ n.level }}</span>
            </span>
            <span class="kb-layer-name">{{ n.label }}</span>
            <span class="kb-layer-desc">{{ n.desc }}</span>
            <span class="kb-layer-foot">
              <span>{{ n.count }} files</span>
              <button type="button" class="kb-chat-entry" @click.stop="chatWithCategory(n)">
                进入对话 <span class="kb-layer-arrow">→</span>
              </button>
            </span>
            <span class="kb-port kb-port-r"></span>
            <span class="kb-port kb-port-b"></span>
          </article>

          <!-- 文件节点 -->
          <article v-for="n in fileNodes" :key="'f' + n.id" class="kb-node kb-fnode"
                   :style="nodeStyle(n)">
            <span class="kb-port kb-port-l"></span>
            <div class="kb-fcard-top">
              <span class="kb-fcard-ico"><Icon name="file" :size="16" /></span>
              <span class="kb-fcard-kind">Excel</span>
              <span class="kb-fcard-time">{{ n.time }}</span>
            </div>
            <h3 class="kb-fcard-title">{{ n.title }}</h3>
            <div class="kb-tags">
              <span v-for="tag in n.tags" :key="tag" class="kb-tag">{{ tag }}</span>
            </div>
            <div class="kb-fcard-foot">
              <span>{{ n.tableCount }} 张表</span>
              <button type="button" class="kb-card-open" @click="openFile(n)">
                打开 <Icon name="external" :size="14" />
              </button>
            </div>
          </article>
        </template>
      </div>

      <div class="kb-zoom" aria-label="画布缩放">
        <button type="button" aria-label="缩小" @click="zoomBy(-0.1)">−</button>
        <span>{{ Math.round(zoom * 100) }}%</span>
        <button type="button" aria-label="放大" @click="zoomBy(0.1)">+</button>
        <button type="button" class="fit" @click="zoom = 1">适配</button>
      </div>

      <div class="kb-minimap" aria-hidden="true">
        <span v-for="n in layerNodes" :key="'ml' + n.id" class="kb-mm-layer"
              :style="{ left: (n.x / VW * 100) + '%', top: (n.y / VH * 100) + '%', background: n.dot }"></span>
        <span v-for="n in fileNodes" :key="'mf' + n.id" class="kb-mm-file"
              :style="{ left: (n.x / VW * 100) + '%', top: (n.y / VH * 100) + '%' }"></span>
        <span class="kb-mm-view"></span>
      </div>
    </div>
  </div>
</template>
