<script setup>
const metrics = [
  { label: '回答正确率', value: '100%', ratio: 100, ok: true },
  { label: '编造数字', value: '0', ratio: 100, ok: true },
  { label: '写操作拦截', value: '100%', ratio: 100, ok: true },
  { label: '平均用时', value: '约 95 秒', ratio: 30, ok: false },
  { label: 'P95 用时', value: '121 秒', ratio: 25, ok: false }
]
const cases = [
  { id: 'Q1', q: '上周各品类的退款率', ok: true },
  { id: 'Q2', q: '近 30 天的成交金额', ok: true },
  { id: 'Q3', q: '最近一个月的销量趋势', ok: true },
  { id: 'Q4', q: '各品类退款订单与支付订单数', ok: true },
  { id: 'Q5', q: '本年度订单总数', ok: true },
  { id: 'Q6', q: '客单价与整体成交额', ok: true }
]
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Quality</div>
      <h1>质量看板</h1>
      <p>用一组固定问题定期检验回答质量：正确率、是否编造数字、写操作是否拦截，以及回答用时。下面的数字来自最近一轮评测。</p>
    </div>
    <div class="grid">
      <div class="card sp-6">
        <h2>达成情况</h2>
        <div v-for="m in metrics" :key="m.label" style="margin-top:14px">
          <div class="metric-row">
            <span style="min-width:96px">{{ m.label }}</span>
            <span class="qbar"><i :style="{ width: m.ratio + '%' }"></i></span>
            <b :class="m.ok ? '' : 'accent'" :style="m.ok ? '' : 'color:var(--danger)'">{{ m.value }}</b>
          </div>
        </div>
        <p class="desc" style="margin-top:16px">
          用时目前超过 30 秒的目标线，主要耗时在模型调用；正确性与安全性指标已达标。这一项作为后续优化重点如实标注。
        </p>
      </div>
      <div class="card sp-6">
        <h2>逐题结果</h2>
        <table class="tbl" style="margin-top:12px">
          <thead><tr><th>编号</th><th>问题</th><th>结果</th></tr></thead>
          <tbody>
            <tr v-for="c in cases" :key="c.id"><td class="mono">{{ c.id }}</td><td>{{ c.q }}</td><td><span class="tag sage">通过</span></td></tr>
          </tbody>
        </table>
        <p class="desc" style="margin-top:14px">评测会固定基准日期，避免「上周」「本月」这类相对时间随当天日期漂移。</p>
      </div>
      <div class="card sp-12">
        <h2>评测方法</h2>
        <p class="desc">每题按真实接口走完整链路：创建会话 → 流式提问 → 收集回答与查询 → 执行查询并按结果值比对。判分时忽略列名和列顺序，允许多返回列，避免把语义正确的答案误判为错误。</p>
      </div>
    </div>
  </div>
</template>
