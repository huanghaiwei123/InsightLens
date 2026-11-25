# InsightLens 前端（Vue 3 + Vite）

8 个页面：概览、对话、上传文件、数据报表、数据资产、处理轨迹、质量看板、设置。
视觉沿用暖米白 + 赤陶的大圆角毛玻璃风格。

## 运行

先启动后端（端口 8080），再：

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173，/personal 自动代理到后端
```

或直接看打包后的页面：`npm run build` 会把产物写进 `../src/main/resources/static/`，
启动后端后访问 `http://localhost:8080/` 即可（无需 dev server）。

## 环境变量（Electron 预留）

采用 hash 路由（`createWebHashHistory`），Electron 用 `file://` 加载页面也不会刷新 404。

| 变量 | 作用 | 默认 |
|------|------|------|
| `VITE_API_BASE` | 接口基址。Web 形态留空走同源相对路径；Electron 形态填 `http://127.0.0.1:<后端端口>` | 空 |
| `VITE_OUT_DIR` | 构建输出目录。Electron 形态可改成 `../dist-electron` 再交给主进程 | `../src/main/resources/static` |

Electron 打包思路（后续）：主进程用 jlink 裁剪 JRE 启动后端 jar，渲染进程 `loadFile` 加载本工程产物，
通过 `VITE_API_BASE` 指向主进程启动的后端端口。

## 接口

见桌面文档 `InsightLens_API_前端对接文档.md`。契约要点：
- 统一返回 `{ code, msg, data }`，`code === "200"` 才算成功
- 对话是 SSE，必须用 POST + fetch ReadableStream 逐帧读，不能用 EventSource
- 上传是 multipart，字段名 `excel`，可多文件，返回结构化结果
