<script setup>
// 数据资产清单是业务化说明（示意），真实表结构随用户上传的 Excel 而定。
const tables = [
  { name: '订单', fields: ['订单编号', '下单时间', '支付时间', '订单状态', '支付金额'], note: '退款率按订单口径计算；时间筛选默认用支付时间。' },
  { name: '退款', fields: ['订单编号', '退款原因', '退款金额', '退款时间'], note: '退款原因用于归类分析，金额与订单表按订单编号关联。' },
  { name: '商品', fields: ['商品编号', '商品名称', '品类', '上架状态'], note: '品类是跨文件查询时的常用维度。' },
  { name: '用户', fields: ['用户编号', '注册时间', '用户层级'], note: '客单价的分母是下单用户数，不是订单数。' },
  { name: '订单明细', fields: ['订单编号', '商品编号', '数量', '单价'], note: '明细金额用于商品维度分析，整体成交额以订单支付金额为准。' }
]
</script>

<template>
  <div class="wrap">
    <div class="page-head">
      <div class="kicker">Assets</div>
      <h1>数据资产</h1>
      <p>系统从你上传的 Excel 里识别数据表。下面是常见业务表的字段与口径说明，帮助你理解回答是怎么算出来的。</p>
    </div>
    <div class="grid">
      <div class="card sp-6" v-for="t in tables" :key="t.name">
        <h2>{{ t.name }}</h2>
        <div style="margin:12px 0">
          <span v-for="f in t.fields" :key="f" class="tag muted" style="margin:3px 5px 3px 0">{{ f }}</span>
        </div>
        <p class="desc"><b>口径说明：</b>{{ t.note }}</p>
      </div>
    </div>
    <p class="small muted" style="margin-top:16px">以上为界面示意。实际可查询的字段以你上传的文件为准。</p>
  </div>
</template>
