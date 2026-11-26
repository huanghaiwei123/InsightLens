<script setup>
/**
 * 内联线性 SVG 图标集：严格无 emoji。
 * 用法：<Icon name="report" :size="14" />
 * 通过 currentColor 继承文字色，stroke 统一线性风格。
 */
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 14 },
  stroke: { type: [Number, String], default: 1.7 }
})

const paths = {
  // 概览
  overview: [
    'M3 3h7v7H3z',
    'M14 3h7v7h-7z',
    'M14 14h7v7h-7z',
    'M3 14h7v7H3z'
  ],
  // 对话
  chat: [
    'M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z'
  ],
  // 上传文件
  upload: [
    'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4',
    'M17 8l-5-5-5 5',
    'M12 3v12'
  ],
  // 数据报表
  report: [
    'M3 3v18h18',
    'M7 16l4-5 3 3 5-7'
  ],
  // 数据资产
  assets: [
    'M12 2l9 5-9 5-9-5 9-5z',
    'M3 12l9 5 9-5',
    'M3 17l9 5 9-5'
  ],
  // 质量看板
  quality: [
    'M12 2l8 4v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6l8-4z',
    'M9 12l2 2 4-4'
  ],
  // 设置
  settings: [
    'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z',
    'M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.04 1.57V21a2 2 0 1 1-4 0v-.09a1.7 1.7 0 0 0-1.11-1.56 1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.57-1.03H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.6 8.9a1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1.03-1.57V3a2 2 0 1 1 4 0v.09A1.7 1.7 0 0 0 15 4.6a1.7 1.7 0 0 0 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.7 1.7 0 0 0 19.4 9v.09a1.7 1.7 0 0 0 1.57 1.04H21a2 2 0 1 1 0 4h-.09a1.7 1.7 0 0 0-1.51.87z'
  ],
  // 品牌 logo（四宫格）
  logo: [
    'M3 3h7v7H3z',
    'M14 3h7v7h-7z',
    'M3 14h7v7H3z',
    'M14 14h7v7h-7z'
  ],
  // 漏斗 / 筛选
  filter: ['M3 5h18l-7 8v6l-4 2v-8L3 5z'],
  // 打开 / 外链
  external: [
    'M14 4h6v6',
    'M20 4l-9 9',
    'M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5'
  ],
  // 文件
  file: [
    'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6z',
    'M14 2v6h6'
  ]
}
</script>

<template>
  <svg
    :width="size" :height="size" viewBox="0 0 24 24"
    fill="none" :stroke-width="stroke"
    stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" focusable="false"
  >
    <path v-for="(d, i) in paths[name] || []" :key="i" :d="d" />
  </svg>
</template>
