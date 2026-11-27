<script setup>
import { ref, computed, onMounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { fetchConfig, fetchReport, downloadExcel, openLocalExcel } from '@/api'
import Icon from '@/components/Icon.vue'
import FilterPanel from '@/components/FilterPanel.vue'

/**
 * 数据报表 / 知识图谱仪表板（全屏悬浮画布）
 * - 分类完全来自后端 /personal/report/categories：大分类 = category.name（中文），
 *   小分类 = file_path 的目录层级；前端不预设任何业务分类，任何数据上传后都能显示。
 * - 画布支持按住空白处拖动（pan）与滚轮缩放（zoom），像 dashboard 一样浏览。
 * - 层级钻取：根层只显示一级分类；点进某个分类后，只显示它的子分类与直属文件；
 *   面包屑可回到任意层级。分类与文件不再拍平混在同一层。
 */
const toast = inject('toast', () => {})
const router = useRouter()
const loading = ref(true)
const workspaceRoot = ref('')
const tree = ref([])
const selected = ref(new Set())

/* 画布视口 */
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragOrigin = ref({ x: 0, y: 0, px: 0, py: 0 })

/* 当前钻取路径：[{ id, name, node }]，空数组 = 根 */
const path = ref([])

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

/* ---------- 当前层级的内容 ---------- */
const currentNode = computed(() => path.value.length ? path.value[path.value.length - 1].node : null)
const currentCategories = computed(() => {
  const node = currentNode.value
  return node ? (node.children || []) : tree.value
})

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

/* 筛选计数基于「当前分类及其所有子分类」的文件，切层后自然更新；根层统计全部分类 */
function collectFiles(nodes) {
  const out = []
  for (const n of nodes || []) {
    out.push(...(n.files || []))
    out.push(...collectFiles(n.children || []))
  }
  return out
}
const filesInScope = computed(() => {
  const node = currentNode.value
  return collectFiles(node ? [node] : tree.value)
})
const groups = computed(() => filterDefs.map((g) => ({
  title: g.title,
  options: g.options.map((o) => ({
    ...o,
    count: filesInScope.value.filter((f) => facetsOf(f).includes(o.id)).length
  }))
})))

/*
 * 当前分类的直属文件（应用筛选）。
 * 不按「是否叶子」拦一道：一个分类完全可以既有子分类、又直接挂着文件，
 * 早期版本在中间层直接返回空数组，这类节点的文件一点进去就消失。
 * 根层没有直属文件，只展示分类。
 */
const currentFiles = computed(() => {
  const node = currentNode.value
  if (!node) return []
  const files = node.files || []
  return files.filter((f) => {
    const facets = facetsOf(f)
    for (const g of filterDefs) {
      const picked = g.options.filter((o) => selected.value.has(o.id)).map((o) => o.id)
      if (picked.length && !picked.some((id) => facets.includes(id))) return false
    }
    return true
  })
})

onMounted(() => {
  Promise.all([fetchReport().catch(() => []), fetchConfig().catch(() => null)])
    .then(([data, config]) => {
      workspaceRoot.value = config?.workspaceRoot || ''
      tree.value = Array.isArray(data) ? data : []
      loading.value = false
    })
    .catch(() => { tree.value = []; loading.value = false })
})

/* ---------- 层级导航 ---------- */
function resetView() { pan.value = { x: 0, y: 0 }; zoom.value = 1 }
function enterCategory(n) {
  // 入参是画布视图节点（只有 raw / label），钻取路径必须存后端的原始树节点，
  // 否则下一层拿不到 children 与 files，进来就是空画布。
  const raw = n.raw || n
  path.value = [...path.value, { id: n.id, name: raw.name || n.label, node: raw }]
  selected.value = new Set()
  resetView()
}
function goToDepth(i) {
  path.value = path.value.slice(0, i)
  selected.value = new Set()
  resetView()
}
function resetFilters() { selected.value = new Set() }

/* ---------- 画布拖拽 / 缩放 ---------- */
const worldStyle = computed(() => ({
  transform: `translate(${pan.value.x}px, ${pan.value.y}px) scale(${zoom.value})`
}))
function onPointerDown(e) {
  if (e.button !== 0) return
  if (e.target.closest('.kb-node, .kb-graph-bar, .kb-zoom, .kb-minimap, button')) return
  dragging.value = true
  dragOrigin.value = { x: e.clientX, y: e.clientY, px: pan.value.x, py: pan.value.y }
  try { e.currentTarget.setPointerCapture(e.pointerId) } catch (_) { /* 老浏览器忽略 */ }
}
function onPointerMove(e) {
  if (!dragging.value) return
  pan.value = {
    x: dragOrigin.value.px + (e.clientX - dragOrigin.value.x),
    y: dragOrigin.value.py + (e.clientY - dragOrigin.value.y)
  }
}
function onPointerUp() { dragging.value = false }
function zoomBy(delta) {
  zoom.value = Math.min(1.8, Math.max(0.5, Math.round((zoom.value + delta) * 10) / 10))
}
function onWheel(e) { zoomBy(e.deltaY < 0 ? 0.1 : -0.1) }

/* ---------- 画布节点布局 ---------- */
const VW = 1200
const VH = 760
const NODE_W = 240
const NODE_H = 160

/*
 * 散落式布局：按节点 id 做确定性伪随机（同一批节点每次进来看着一致，不会乱跳），
 * 再做占位避让，保证卡片不重叠。不用网格，视觉上像铺在画布上的便签。
 */
function hashString(s) {
  let h = 2166136261
  for (let i = 0; i < String(s).length; i++) {
    h ^= String(s).charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}
function scatter(items, salt) {
  const placed = []
  return items.map((n) => {
    // 每次换一个种子重掷，最多 60 次；取第一个不撞的位置，撞太多则用最后候选
    let best = null
    for (let attempt = 0; attempt < 60; attempt++) {
      const r = hashString(`${salt}|${n.id}|${attempt}`)
      const x = 60 + (r % 1000) / 1000 * (VW - NODE_W - 120)
      const y = 210 + ((r >>> 10) % 1000) / 1000 * (VH - NODE_H - 250)
      const overlaps = placed.some((p) =>
        Math.abs(p.x - x) < NODE_W + 24 && Math.abs(p.y - y) < NODE_H + 16)
      best = { x, y }
      if (!overlaps) break
    }
    placed.push(best)
    return { ...n, x: best.x, y: best.y }
  })
}

/* 分类卡片的副标题：子分类与直属文件可能同时存在，数量分开说清，避免「进入后空」的预期错位 */
function describeNode(n) {
  const childCount = (n.children || []).length
  const directFiles = (n.files || []).length
  if (childCount && directFiles) return `${childCount} 个子分类 · ${directFiles} 个文件`
  if (childCount) return `${childCount} 个子分类`
  if (directFiles) return `${directFiles} 个文件`
  return '暂无内容'
}

const allNodes = computed(() => {
  const cats = currentCategories.value.map((n, i) => ({
    id: 'cat-' + (n.id ?? n.name),
    kind: 'category',
    raw: n,
    label: n.name,
    desc: describeNode(n),
    level: (n.children || []).length ? 'moderate' : 'simple',
    dot: dot((i % 7) + 1),
    delay: -(i * 1.1)
  }))
  const files = currentFiles.value.map((f, i) => {
    const sheets = f.sheets || []
    const rows = sheets.reduce((s, x) => s + (x.rowCount || 0), 0)
    return {
      id: 'file-' + f.uploadId,
      kind: 'file',
      real: f,
      title: f.origName || '未命名文件',
      tags: [`${sheets.length} 张表`, `${fmtRows(rows)} 行`, fmtTime(f.createdAt)].filter(Boolean),
      tableCount: sheets.length,
      time: fmtTime(f.createdAt),
      delay: -(i * 0.9 + 0.4)
    }
  })
  // 两类节点一起散落，避免分类卡和文件卡互相压住
  return scatter([...cats, ...files], path.value.map((p) => p.id).join('/'))
})
const categoryNodes = computed(() => allNodes.value.filter((n) => n.kind === 'category'))
const fileNodes = computed(() => allNodes.value.filter((n) => n.kind === 'file'))

/* 顶栏计数：同一层可能既有子分类又有文件，两者都要显示 */
const scopeLabel = computed(() => {
  const c = categoryNodes.value.length
  const f = fileNodes.value.length
  if (c && f) return `${c} 个分类 · ${f} 个文件`
  return c ? `${c} 个分类` : `${f} 个文件`
})

/* 钻取后：从画布顶部「当前位置」锚点连线到每个子节点 */
const edges = computed(() => {
  if (!path.value.length) return []
  const ax = VW / 2
  const ay = 190
  const out = []
  for (const list of [categoryNodes.value, fileNodes.value]) {
    for (const n of list) {
      const x2 = n.x + NODE_W / 2
      const y2 = n.y
      const my = (ay + y2) / 2
      out.push({ id: 'e-' + n.id, d: `M ${ax} ${ay} C ${ax} ${my}, ${x2} ${my}, ${x2} ${y2}` })
    }
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

/* ---------- 打开文件 / 进入对话 ---------- */
async function openFile(card) {
  const relative = card.real.filePath || card.real.origName
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
function chatWithCategory(n) {
  const raw = n.raw || n
  router.push({ path: '/chat', query: { categoryId: String(raw.id), categoryName: raw.name } })
}
</script>

<template>
  <div class="kb-scope kb-page kb-graph-page">
    <div class="kb-canvas"
         :class="{ dragging }"
         @pointerdown="onPointerDown"
         @pointermove="onPointerMove"
         @pointerup="onPointerUp"
         @pointercancel="onPointerUp"
         @wheel.prevent="onWheel">
      <!-- 悬浮控制条 -->
      <div class="kb-graph-bar">
        <div class="kb-graph-bar-row">
          <div class="kb-graph-title">
            <span class="kb-eyebrow">Reports</span>
            <h1>数据报表</h1>
          </div>
          <nav class="kb-crumb" aria-label="面包屑">
            <button type="button" class="kb-crumb-item"
                    :class="{ here: !path.length }" @click="goToDepth(0)">全部数据</button>
            <template v-for="(seg, i) in path" :key="seg.id">
              <span class="kb-crumb-sep">/</span>
              <button type="button" class="kb-crumb-item"
                      :class="{ here: i === path.length - 1 }"
                      @click="goToDepth(i + 1)">{{ seg.name }}</button>
            </template>
          </nav>
          <FilterPanel v-model="selected" :groups="groups" />
          <span class="kb-count">{{ scopeLabel }}</span>
        </div>
        <div class="kb-bar-bottom">
          <span class="kb-drag-hint">按住空白处拖动 · 滚轮缩放 · 点分类进入</span>
        </div>
      </div>

      <!-- 悬浮画布世界 -->
      <div class="kb-world" :class="{ dragging }" :style="worldStyle">
        <svg class="kb-edges" :viewBox="`0 0 ${VW} ${VH}`" preserveAspectRatio="none" aria-hidden="true">
          <path v-for="e in edges" :key="e.id" :d="e.d" class="kb-edge" />
          <circle v-if="path.length" :cx="VW / 2" :cy="190" r="5" class="kb-edge-anchor" />
        </svg>

        <div v-if="loading" class="kb-empty">正在读取数据…</div>
        <div v-else-if="!categoryNodes.length && !fileNodes.length" class="kb-empty">
          <div class="big">∅</div>
          <div>{{ path.length ? '这个分类下还没有文件或子分类' : '还没有数据，先去上传文件吧' }}</div>
          <div class="small">
            <button v-if="selected.size" type="button" class="kb-card-open" @click="resetFilters">清除筛选</button>
            <button v-else-if="path.length" type="button" class="kb-card-open" @click="goToDepth(path.length - 1)">返回上一级</button>
          </div>
        </div>
        <template v-else>
          <!-- 子分类节点：名称来自后端；点卡片进入下一层 -->
          <article v-for="n in categoryNodes" :key="n.id"
                   class="kb-node kb-layer"
                   :style="nodeStyle(n)" @click="enterCategory(n)">
            <span class="kb-layer-stripe" :style="{ background: n.dot }"></span>
            <span class="kb-layer-top">
              <span class="kb-node-eyebrow">Layer</span>
              <span class="kb-layer-level">{{ n.level }}</span>
            </span>
            <span class="kb-layer-name">{{ n.label }}</span>
            <span class="kb-layer-desc">{{ n.desc }}</span>
            <span class="kb-layer-foot">
              <span class="kb-enter-hint">查看内容 · 进入 →</span>
              <button type="button" class="kb-chat-entry" @click.stop="chatWithCategory(n)">
                进入对话 <span class="kb-layer-arrow">→</span>
              </button>
            </span>
            <span class="kb-port kb-port-b"></span>
          </article>

          <!-- 当前分类直属文件 -->
          <article v-for="n in fileNodes" :key="n.id" class="kb-node kb-fnode"
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
        <button type="button" class="fit" @click="resetView">适配</button>
      </div>

      <div class="kb-minimap" aria-hidden="true">
        <span v-for="n in categoryNodes" :key="'ml' + n.id" class="kb-mm-layer"
              :style="{ left: (n.x / VW * 100) + '%', top: (n.y / VH * 100) + '%', background: n.dot }"></span>
        <span v-for="n in fileNodes" :key="'mf' + n.id" class="kb-mm-file"
              :style="{ left: (n.x / VW * 100) + '%', top: (n.y / VH * 100) + '%' }"></span>
        <span class="kb-mm-view"></span>
      </div>
    </div>
  </div>
</template>
