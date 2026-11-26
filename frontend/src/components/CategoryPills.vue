<script setup>
/**
 * 组件B：顶部分类胶囊条 + 右侧层图例
 * - 胶囊：11px 字距、圆角 999、左侧 8px 圆点
 *   激活 = --soft 底 + --accent 文字；未激活 = 透明 + --t2；
 *   关闭态 = --t3/50 + 文字删除线 + 圆点 opacity .35
 * - 容器 overflow-x:auto 且隐藏滚动条
 * - 层图例：圆点 + 名称 + (数量)，激活项加粗，其余 opacity .45
 */
defineProps({
  pills: { type: Array, required: true },      // [{ id, label, dot, disabled }]
  activeId: { type: String, default: '' },
  legend: { type: Array, default: () => [] },  // [{ id, label, dot, count }]
  activeLegend: { type: String, default: '' }
})
const emit = defineEmits(['select', 'select-legend'])
</script>

<template>
  <div class="kb-pillbar">
    <div class="kb-pills" role="tablist" aria-label="数据分类">
      <button
        v-for="p in pills"
        :key="p.id"
        type="button"
        class="kb-pill"
        :class="{ active: p.id === activeId, closed: p.disabled }"
        :role="p.disabled ? undefined : 'tab'"
        :aria-selected="p.disabled ? undefined : (p.id === activeId ? 'true' : 'false')"
        :disabled="p.disabled"
        @click="!p.disabled && emit('select', p.id)"
      >
        <span class="kb-pill-dot" :style="{ background: p.dot }" aria-hidden="true"></span>
        <span class="kb-pill-label">{{ p.label }}</span>
      </button>
    </div>

    <div v-if="legend.length" class="kb-legend" aria-label="层图例">
      <button
        v-for="l in legend"
        :key="l.id"
        type="button"
        class="kb-legend-item"
        :class="{ active: l.id === activeLegend }"
        :aria-pressed="l.id === activeLegend ? 'true' : 'false'"
        @click="emit('select-legend', l.id)"
      >
        <span class="kb-fdot" :style="{ background: l.dot }" aria-hidden="true"></span>
        <span>{{ l.label }}</span>
        <span class="kb-legend-num">({{ l.count }})</span>
      </button>
    </div>
  </div>
</template>
