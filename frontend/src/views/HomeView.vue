<script setup>
import { inject, ref, onMounted } from 'vue'
import { fetchReport } from '@/api'

const toast = inject('toast')
const report = ref([])
const stats = ref({ files: 0, sheets: 0, rows: 0 })

function sum(nodes, acc) {
  nodes.forEach((n) => {
    acc.files += n.fileCount || 0
    acc.sheets += n.sheetCount || 0
    acc.rows += n.totalRows || 0
    if (n.children) sum(n.children, acc)
  })
  return acc
}

onMounted(() => {
  fetchReport().then((d) => {
    report.value = d || []
    stats.value = sum(report.value, { files: 0, sheets: 0, rows: 0 })
  }).catch((e) => toast('暂时读不到数据目录：' + e.message))
})

const steps = [
  { n: '01', t: '放好表格', d: '把 Excel 上传到工作目录，系统自动读懂每个 sheet 并归类。' },
  { n: '02', t: '直接提问', d: '用日常语言问数据，不用记字段名——「各科平均分多少」「哪些商品压了库存」都行。' },
  { n: '03', t: '查看报表', d: '按分类浏览已登记的数据资产，随时定位到原始文件。' }
]
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Overview</div>
      <h1>把表格变成答案</h1>
      <p>不用懂数据库，也不用写查询。上传你的 Excel，像聊天一样提问，InsightLens 会基于真实数据给出可追溯的回答。</p>
    </div>

    <div class="grid">
      <div class="card stat sp-3"><div class="num accent">{{ stats.files }}</div><div class="lbl">已登记文件</div></div>
      <div class="card stat sp-3"><div class="num">{{ stats.sheets }}</div><div class="lbl">数据表（sheet）</div></div>
      <div class="card stat sp-3"><div class="num">{{ stats.rows.toLocaleString() }}</div><div class="lbl">可查询数据行</div></div>
      <div class="card stat sp-3"><div class="num">{{ report.length }}</div><div class="lbl">数据分类</div></div>

      <div class="card sp-12">
        <h2>三步开始</h2>
        <p class="desc">从一份 Excel 到一个答案，只需要三步。</p>
        <div class="grid" style="margin-top:18px">
          <div v-for="s in steps" :key="s.n" class="sp-4">
            <span class="tag terra">{{ s.n }}</span>
            <h2 style="margin-top:12px">{{ s.t }}</h2>
            <p class="desc">{{ s.d }}</p>
          </div>
        </div>
      </div>

      <div class="card sp-8">
        <h2>当前的数据分类</h2>
        <p class="desc">分类来自你上传的文件；每个分类下可以直接提问，也可以打开报表定位文件。</p>
        <div v-if="report.length" style="margin-top:14px">
          <div v-for="n in report" :key="n.id" class="tree-file">
            <span class="tag sage">{{ n.fileCount }} 文件</span>
            <span class="name" style="flex:1;font-weight:600">{{ n.name }}</span>
            <span class="small muted">{{ n.totalRows.toLocaleString() }} 行</span>
          </div>
        </div>
        <div v-else class="empty small">还没有数据，先去「上传文件」放一份 Excel 吧。</div>
      </div>

      <div class="card sp-4">
        <h2>它怎么理解你的问题</h2>
        <p class="desc">每一轮回答都会经历：理解问题 → 找到可用数据 → 生成查询 → 安全检查 → 取数 → 核对 → 整理成自然语言。全过程可追溯，出错会自动修正，最多两次。</p>
        <div style="margin-top:14px">
          <span class="tag sage">只读查询</span>
          <span class="tag gold" style="margin-left:6px">口径优先</span>
        </div>
      </div>
    </div>
  </div>
</template>
