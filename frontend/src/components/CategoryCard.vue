<script setup>
import { computed, inject } from 'vue'

const props = defineProps({
  node: { type: Object, required: true }
})
const injectToast = inject('toast', () => {})
const emit = defineEmits(['drill'])

const childCount = computed(() => (props.node.children || []).length)
const fileCount = computed(() => (props.node.files || []).length)

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function fmtRows(n) {
  n = n || 0
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : n.toLocaleString()
}
</script>

<template>
  <button type="button" class="cat-card" @click="emit('drill', node)">
    <div class="cat-card-top">
      <span class="cat-glyph">▦</span>
      <span class="cat-kind">分类</span>
      <span v-if="node.createdAt" class="cat-time">{{ fmtTime(node.createdAt) }}</span>
    </div>
    <div class="cat-name">{{ node.name }}</div>
    <div class="cat-desc">
      <template v-if="childCount">{{ childCount }} 个子分类</template>
      <template v-else-if="fileCount">{{ fileCount }} 个文件</template>
      <template v-else>空分类</template>
      <template v-if="node.sheetCount"><span class="dot-sep">·</span>{{ node.sheetCount }} 张表</template>
      <template v-if="node.totalRows"><span class="dot-sep">·</span>{{ fmtRows(node.totalRows) }} 行</template>
    </div>
    <div class="cat-card-foot">进入查看 <span>→</span></div>
  </button>
</template>
