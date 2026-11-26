<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import Icon from './Icon.vue'

/**
 * 组件A：悬浮分类筛选面板
 * - 触发按钮（漏斗 + 筛选），有激活项时胶囊高亮并显示数量徽标
 * - 浮层：4 组复选，Set 管理，实时向外抛出选中集
 * - 交互：点外部关闭（mousedown + contains）、Esc 关闭、F 键开合
 * - 无障碍：aria-expanded / aria-checked，Tab 可达，按钮原生支持 Enter/Space
 */
const props = defineProps({
  groups: { type: Array, required: true },           // [{ title, options:[{id,label,count,dot}] }]
  modelValue: { type: Set, required: true }          // 选中的 option id 集合
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const root = ref(null)
const selectedCount = computed(() => props.modelValue.size)

function toggleOpen() { open.value = !open.value }
function close() { open.value = false }

function toggle(id) {
  const next = new Set(props.modelValue)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  emit('update:modelValue', next)
}

function reset() {
  emit('update:modelValue', new Set())
}

function onDocMousedown(e) {
  if (open.value && root.value && !root.value.contains(e.target)) close()
}
function isTyping(target) {
  if (!target) return false
  const tag = (target.tagName || '').toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable
}
function onKeydown(e) {
  if (e.key === 'Escape' && open.value) { close(); return }
  if ((e.key === 'f' || e.key === 'F') && !isTyping(e.target) && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    toggleOpen()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMousedown)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMousedown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="root" class="kb-filter">
    <button
      type="button"
      class="kb-filter-trigger"
      :class="{ active: open || selectedCount > 0 }"
      :aria-expanded="open ? 'true' : 'false'"
      aria-haspopup="true"
      aria-controls="kb-filter-pop"
      @click="toggleOpen"
    >
      <Icon name="filter" :size="14" />
      <span>筛选</span>
      <span v-if="selectedCount > 0" class="kb-filter-badge">{{ selectedCount }}</span>
    </button>

    <div
      v-if="open"
      id="kb-filter-pop"
      class="kb-filter-pop open"
      role="group"
      aria-label="分类筛选"
    >
      <div class="kb-filter-body">
        <div v-for="(group, gi) in groups" :key="gi" class="kb-fgroup" role="group" :aria-label="group.title">
          <div class="kb-fgroup-title">{{ group.title }}</div>
          <button
            v-for="opt in group.options"
            :key="opt.id"
            type="button"
            class="kb-frow"
            role="checkbox"
            :aria-checked="modelValue.has(opt.id) ? 'true' : 'false'"
            @click="toggle(opt.id)"
          >
            <span class="kb-fcheck" aria-hidden="true">
              <svg v-if="modelValue.has(opt.id)" width="10" height="10" viewBox="0 0 24 24"
                   fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M20 6L9 17l-5-5" />
              </svg>
            </span>
            <span class="kb-fdot" :style="{ background: opt.dot }" aria-hidden="true"></span>
            <span class="kb-flabel">{{ opt.label }}</span>
            <span class="kb-fnum">{{ opt.count }}</span>
          </button>
        </div>
      </div>

      <div v-if="selectedCount > 0" class="kb-filter-foot">
        <button type="button" class="kb-filter-reset" @click="reset">重置全部</button>
      </div>
    </div>
  </div>
</template>
