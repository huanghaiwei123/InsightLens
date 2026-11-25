<script setup>
const nodes = [
  { t: '理解问题', d: '先读懂你的问题，确认时间范围、口径和是否需要追问；已定义的口径以数据字典为准。' },
  { t: '查找可用数据', d: '按当前分类收集可用的数据表，作为本次查询的范围与白名单。' },
  { t: '生成查询', d: '依据口径把问题转成只读查询，选表与写条件在这一步一并完成。' },
  { t: '安全检查', d: '拦截写操作、越权表和外部文件读取，自动补上限行数，只放行只读查询。' },
  { t: '取数', d: '在只读引擎上执行查询，拿到真实数据行。' },
  { t: '核对结果', d: '检查结果是否答到了问题上；不行就修正查询重试，最多修正两次。' },
  { t: '整理回答', d: '把结果组织成自然语言回答，逐字显示给你。' }
]
const notes = ['最多自动修正两次，超过就如实说明', '跳过的环节也会留痕，方便追溯', '追问口径不算失败，是正常确认']
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Trace</div>
      <h1>处理轨迹</h1>
      <p>每一轮问答都会经过下面七个环节。回答下方的「经过」一栏会显示本轮实际走过的步骤，方便你判断答案是怎么来的。</p>
    </div>
    <div class="grid">
      <div class="card sp-8">
        <div class="timeline">
          <div class="tl-item" v-for="(n, i) in nodes" :key="n.t">
            <b>{{ i + 1 }}. {{ n.t }}</b>
            <p>{{ n.d }}</p>
          </div>
        </div>
      </div>
      <div class="card sp-4">
        <h2>你会看到的说明</h2>
        <p class="desc">这些是系统对回答过程的约定：</p>
        <div style="margin-top:12px">
          <div v-for="x in notes" :key="x" class="tree-file"><span>✓</span><span style="flex:1">{{ x }}</span></div>
        </div>
        <p class="desc" style="margin-top:16px">在「设置」里可以关闭回答下方的环节显示。</p>
      </div>
    </div>
  </div>
</template>
