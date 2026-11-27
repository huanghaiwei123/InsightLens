/**
 * 打包入口：npm run dist
 *
 * 存在的唯一理由：electron-builder 首次打包要下载 7zip / nsis / nsis-resources，
 * 直连 GitHub 在国内经常 502（实测两次全挂）。这里默认指向 npmmirror 镜像，
 * 需要覆盖时设 ELECTRON_BUILDER_BINARIES_MIRROR 即可。
 *
 * 注意：electron-builder 的 --dir 不生成 app-update.yml（只有 nsis 目标才写），
 * 所以验证自动更新必须跑完整打包，不能用 --dir 代替。
 */
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { version } = require('../package.json')

const MIRROR = 'https://npmmirror.com/mirrors/electron-builder-binaries/'
const env = {
  ...process.env,
  ELECTRON_BUILDER_BINARIES_MIRROR: process.env.ELECTRON_BUILDER_BINARIES_MIRROR || MIRROR
}

console.log(`[dist] 打包 InsightLens ${version}`)
console.log(`[dist] 二进件镜像 ${env.ELECTRON_BUILDER_BINARIES_MIRROR}`)

const extra = process.argv.slice(2)
const result = spawnSync('npx', ['electron-builder', '--win', ...extra], {
  stdio: 'inherit',
  env,
  shell: process.platform === 'win32'
})

if (result.status !== 0) {
  console.error('[dist] 打包失败。常见原因：')
  console.error('  - 网络：换个镜像 ELECTRON_BUILDER_BINARIES_MIRROR=https://...')
  console.error('  - 文件占用：先关掉正在运行的 release\\win-unpacked\\InsightLens.exe')
  process.exit(result.status || 1)
}

console.log(`[dist] 完成。发布需要上传 ${'release/'} 下这三个文件：`)
console.log(`  InsightLens-Setup-${version}.exe`)
console.log(`  InsightLens-Setup-${version}.exe.blockmap   （差分下载用，别漏）`)
console.log('  latest.yml                                   （版本清单，别漏）')
