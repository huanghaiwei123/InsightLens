import { createRouter, createWebHashHistory } from 'vue-router'

// 用 hash 模式：Electron 生产环境以 file:// 加载页面，history 模式刷新会 404。
// Web 形态（Spring Boot 托管）下 hash 同样可用，只是 URL 多个 #。
const routes = [
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { title: '概览' } },
  { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '对话' } },
  { path: '/upload', name: 'upload', component: () => import('@/views/UploadView.vue'), meta: { title: '上传文件' } },
  { path: '/report', name: 'report', component: () => import('@/views/ReportView.vue'), meta: { title: '数据报表' } },
  { path: '/assets', name: 'assets', component: () => import('@/views/AssetsView.vue'), meta: { title: '数据资产' } },
  { path: '/trace', name: 'trace', component: () => import('@/views/TraceView.vue'), meta: { title: '处理轨迹' } },
  { path: '/quality', name: 'quality', component: () => import('@/views/QualityView.vue'), meta: { title: '质量看板' } },
  { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置' } }
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})
