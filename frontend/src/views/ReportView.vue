<script setup>
import { ref, computed, onMounted, inject } from 'vue'
import { fetchConfig, fetchReport, downloadExcel, openLocalExcel } from '@/api'
import Icon from '@/components/Icon.vue'
import FilterPanel from '@/components/FilterPanel.vue'
import CategoryPills from '@/components/CategoryPills.vue'

/**
 * 数据报表 / 知识图谱仪表板（全屏悬浮画布）
 * - 画布铺满视口，节点散布、轻微漂浮，节点间连线，无外层边框束缚
 * - 配色沿用米白暖橙规范；筛选/胶囊/图例浮在画布上方
 * - 数据策略：优先真实分类，接口为空或失败回落 mock
 */
const toast = inject('toast', () => {})
const loading = ref(true)
const workspaceRoot = ref('')
const cards = ref([])
const selected = ref(new Set())
const activePill = ref('all')
const activeLegend = ref('')
const zoom = ref(1)

const dot = (n) => `var(--dot-${n})`

/* ---------- 筛选维度（4 组，计数从当前卡片实时统计） ---------- */
const filterDefs = [
  {
    title: '数据类型',
    options: [
      { id: 'eval', label: '评测数据', dot: dot(1) },
      { id: 'biz', label: '业务明细', dot: dot(2) },
      { id: 'dim', label: '维度字典', dot: dot(3) },
      { id: 'log', label: '运行日志', dot: dot(4) }
    ]
  },
  {
    title: '数据来源',
    options: [
      { id: 'upload', label: '本地上传', dot: dot(5) },
      { id: 'db', label: '数据库连接', dot: dot(6) },
      { id: 'mcp', label: 'MCP 联网', dot: dot(7) }
    ]
  },
  {
    title: '状态',
    options: [
      { id: 'ready', label: '已归类', dot: dot(4) },
      { id: 'check', label: '待校验', dot: dot(2) },
      { id: 'archive', label: '已归档', dot: dot(7) }
    ]
  },
  {
    title: '时间范围',
    options: [
      { id: '7d', label: '近 7 天', dot: dot(1) },
      { id: '30d', label: '近 30 天', dot: dot(3) },
      { id: '90d', label: '近 90 天', dot: dot(5) },
      { id: 'old', label: '更早', dot: dot(6) }
    ]
  }
]
const groups = computed(() => filterDefs.map((g) => ({
  title: g.title,
  options: g.options.map((o) => ({ ...o, count: cards.value.filter((c) => c.facets.includes(o.id)).length }))
})))

/* ---------- 分类胶囊 / 层图例 ---------- */
const pillDefs = [
  { id: 'all', label: '全部数据', dot: dot(1) },
  { id: 'eval', label: '评测数据', dot: dot(2) },
  { id: 'biz', label: '电商运营', dot: dot(4) },
  { id: 'order', label: '历史订单', dot: dot(5) },
  { id: 'refund', label: '退款分析', dot: dot(6) },
  { id: 'dim', label: '维度字典', dot: dot(3) }
]
const pills = computed(() => pillDefs.map((p) => ({
  ...p,
  disabled: p.id !== 'all' && !cards.value.some((c) => c.categoryId === p.id)
})))
const legendDefs = [
  { id: 'data', label: '数据层', dot: dot(1) },
  { id: 'biz', label: '业务层', dot: dot(4) },
  { id: 'time', label: '时间层', dot: dot(5) },
  { id: 'state', label: '状态层', dot: dot(6) }
]
const legend = computed(() => legendDefs.map((l) => ({
  ...l, count: cards.value.filter((c) => c.layerId === l.id).length
})))

/* ---------- 内置 mock ---------- */
const mockCards = [
  {
    id: 'm1', title: '评测基准集 · 电商问数',
    tags: ['评测数据', '本地上传', '已归类'], tableCount: 3,
    facets: ['eval', 'upload', 'ready', '7d'], categoryId: 'eval', layerId: 'data',
    time: '16:32', createdAt: '2026-11-24 16:32', real: null, segments: []
  },
  {
    id: 'm2', title: '订单明细 · 近 90 天快照',
    tags: ['业务明细', '数据库连接', '待校验'], tableCount: 2,
    facets: ['biz', 'db', 'check', '90d'], categoryId: 'order', layerId: 'biz',
    time: '09:18', createdAt: '2026-11-21 09:18', real: null, segments: []
  },
  {
    id: 'm3', title: '品类口径词典 v0.3',
    tags: ['维度字典', '本地上传', '已归档'], tableCount: 1,
    facets: ['dim', 'upload', 'archive', '30d'], categoryId: 'dim', layerId: 'time',
    time: '14:05', createdAt: '2026-11-18 14:05', real: null, segments: []
  }
]

/* ---------- 真实数据 → 卡片 ---------- */
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(11, 16) : '' }
function timeBucket(t) {
  if (!t) return 'old'
  const days = (Date.now() - new Date(String(t).replace(' ', 'T')).getTime()) / 86400000
  if (days <= 7) return '7d'
  if (days <= 30) return '30d'
  if (days <= 90) return '90d'
  return 'old'
}
function matchPill(text) {
  if (/评测/.test(text)) return 'eval'
  if (/退款/.test(text)) return 'refund'
  if (/维度|字典|口径/.test(text)) return 'dim'
  if (/订单|历史/.test(text)) return 'order'
  if (/电商|运营/.test(text)) return 'biz'
  return 'biz'
}
function flatten(nodes, path = []) {
  const out = []
  for (const n of nodes || []) {
    const seg = [...path, n.name]
    for (const f of n.files || []) out.push({ file: f, segments: seg })
    out.push(...flatten(n.children || [], seg))
  }
  return out
}
function toCard(entry, i) {
  const text = [...entry.segments, entry.file.origName || ''].join('/')
  const type = /评测/.test(text) ? 'eval' : (/维度|字典|口径/.test(text) ? 'dim' : 'biz')
  const typeLabel = { eval: '评测数据', dim: '维度字典', biz: '业务明细' }[type]
  const bucket = timeBucket(entry.file.createdAt)
  const bucketLabel = { '7d': '近 7 天', '30d': '近 30 天', '90d': '近 90 天', old: '更早' }[bucket]
  return {
    id: entry.file.uploadId || `r${i}`,
    title: entry.file.origName || '未命名文件',
    tags: [typeLabel, '本地上传', '已归类', bucketLabel].slice(0, 3),
    tableCount: (entry.file.sheets || []).length,
    facets: [type, 'upload', 'ready', bucket],
    categoryId: matchPill(text),
    layerId: type === 'eval' ? 'data' : (type === 'dim' ? 'time' : 'biz'),
    time: fmtTime(entry.file.createdAt),
    createdAt: entry.file.createdAt,
    real: entry.file,
    segments: entry.segments
  }
}

onMounted(() => {
  Promise.all([fetchReport().catch(() => []), fetchConfig().catch(() => null)])
    .then(([tree, config]) => {
      workspaceRoot.value = config?.workspaceRoot || ''
      const real = flatten(tree).map(toCard)
      // 真实数据优先；并入 mock 演示节点铺满画布（按文件名去重），形成图谱视图
      const seen = new Set(real.map((c) => c.title))
      cards.value = [...real, ...mockCards.filter((c) => !seen.has(c.title))]
      loading.value = false
    })
    .catch(() => { cards.value = mockCards; loading.value = false })
})

/* ---------- 过滤：组内 OR，组间 AND ---------- */
const visibleCards = computed(() => cards.value.filter((c) => {
  if (activePill.value !== 'all' && c.categoryId !== activePill.value) return false
  if (activeLegend.value && c.layerId !== activeLegend.value) return false
  for (const g of filterDefs) {
    const picked = g.options.filter((o) => selected.value.has(o.id)).map((o) => o.id)
    if (picked.length && !picked.some((id) => c.facets.includes(id))) return false
  }
  return true
}))
const categoryCount = computed(() => new Set(visibleCards.value.map((c) => c.categoryId)).size)
const currentPill = computed(() => pillDefs.find((p) => p.id === activePill.value) || pillDefs[0])
function resetFilters() { selected.value = new Set(); activePill.value = 'all'; activeLegend.value = '' }

/* ---------- 画布节点：分类层节点 + 文件节点 ---------- */
const VW = 1200
const VH = 720
const NODE_W = 240
const LAYER_H = 118
const FILE_H = 152

const layerSlots = [
  { x: 64, y: 268 }, { x: 470, y: 232 }, { x: 884, y: 270 }, { x: 300, y: 528 }
]
const fileSlots = [
  { x: 70, y: 430 }, { x: 430, y: 470 }, { x: 820, y: 430 },
  { x: 248, y: 566 }, { x: 610, y: 566 }, { x: 930, y: 566 }
]
const layerMeta = {
  eval: { label: '评测基准', desc: '问数基准集与评分口径', dot: dot(1) },
  biz: { label: '电商运营', desc: '运营明细与业务指标', dot: dot(4) },
  order: { label: '历史订单', desc: '近 90 天订单快照', dot: dot(5) },
  refund: { label: '退款分析', desc: '退款率与原因归类', dot: dot(6) },
  dim: { label: '维度字典', desc: '品类与指标口径', dot: dot(3) }
}

const layerNodes = computed(() => {
  const ids = [...new Set(visibleCards.value.map((c) => c.categoryId))].slice(0, 4)
  return ids.map((id, i) => {
    const meta = layerMeta[id] || { label: id, desc: '数据分类', dot: dot(7) }
    const count = visibleCards.value.filter((c) => c.categoryId === id).length
    return {
      id: 'layer-' + id, kind: 'layer', categoryId: id,
      label: meta.label, desc: meta.desc, dot: meta.dot,
      count, level: count <= 2 ? 'simple' : 'moderate',
      x: layerSlots[i % layerSlots.length].x,
      y: layerSlots[i % layerSlots.length].y,
      delay: -(i * 1.3)
    }
  })
})
const fileNodes = computed(() => visibleCards.value.slice(0, fileSlots.length).map((c, i) => ({
  ...c,
  kind: 'file',
  x: fileSlots[i % fileSlots.length].x,
  y: fileSlots[i % fileSlots.length].y,
  delay: -(i * 0.9 + 0.4)
})))

/* 连线：每个文件节点接到同分类的层节点 */
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
      d: `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`,
      dot: l.dot
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

/* ---------- 打开文件 / 点击层节点筛选 ---------- */
async function openFile(card) {
  if (!card.real) { toast('这是演示数据，没有对应的 Excel 文件'); return }
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
</script>

<template>
  <div class="kb-scope kb-page kb-graph-page">
    <div class="kb-canvas">
      <!-- 悬浮控制条：面包屑 / 筛选 / 计数 / 胶囊条 -->
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
          <span class="kb-count">{{ categoryCount }} 个分类 · {{ visibleCards.length }} 个文件</span>
        </div>
        <CategoryPills
          :pills="pills" :active-id="activePill"
          :legend="legend" :active-legend="activeLegend"
          @select="activePill = $event"
          @select-legend="activeLegend = activeLegend === $event ? '' : $event"
        />
      </div>

      <!-- 悬浮画布世界：节点 + 连线一起缩放 -->
      <div class="kb-world" :style="{ transform: 'scale(' + zoom + ')' }">
        <svg class="kb-edges" :viewBox="`0 0 ${VW} ${VH}`" preserveAspectRatio="none" aria-hidden="true">
          <path v-for="e in edges" :key="e.id" :d="e.d" class="kb-edge" />
        </svg>

        <div v-if="loading" class="kb-empty">正在读取数据…</div>
        <template v-else-if="!visibleCards.length">
          <div class="kb-empty">
            <div class="big">∅</div>
            <div>当前筛选条件下没有数据</div>
            <div class="small"><button type="button" class="kb-card-open" @click="resetFilters">清除筛选</button></div>
          </div>
        </template>
        <template v-else>
          <!-- 分类层节点 -->
          <button v-for="n in layerNodes" :key="n.id" type="button"
                  class="kb-node kb-layer" :class="{ 'is-active': activePill === n.categoryId }"
                  :style="nodeStyle(n)" @click="pickLayer(n)">
            <span class="kb-layer-stripe" :style="{ background: n.dot }"></span>
            <span class="kb-layer-top">
              <span class="kb-node-eyebrow">Layer</span>
              <span class="kb-layer-level">{{ n.level }}</span>
            </span>
            <span class="kb-layer-name">{{ n.label }}</span>
            <span class="kb-layer-desc">{{ n.desc }}</span>
            <span class="kb-layer-foot">{{ n.count }} files <span class="kb-layer-arrow">→</span></span>
            <span class="kb-port kb-port-r"></span>
            <span class="kb-port kb-port-b"></span>
          </button>

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

      <!-- 左下缩放控件 -->
      <div class="kb-zoom" aria-label="画布缩放">
        <button type="button" aria-label="缩小" @click="zoomBy(-0.1)">−</button>
        <span>{{ Math.round(zoom * 100) }}%</span>
        <button type="button" aria-label="放大" @click="zoomBy(0.1)">+</button>
        <button type="button" class="fit" @click="zoom = 1">适配</button>
      </div>

      <!-- 右下迷你图 -->
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
