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

## Electron 运行

```bash
cd frontend
npm install          # 首次会安装 electron
npm run build        # 先产出 ../src/main/resources/static
mvnw -f ..\\pom.xml package   # 生成 target/InsightLens-0.0.1-SNAPSHOT.jar
npm run electron
```

主进程会启动后端 jar（用 `app.getPath('userData')` 作为 `-Dinsightlens.home`），
等 `/personal/config` 通了之后直接 `loadURL('http://127.0.0.1:8080')`——静态页面本来就由后端托管，
不需要 `VITE_API_BASE`。`preload.cjs` 只暴露 `openFile` / `showItemInFolder`，且限制路径必须在工作区内。

## 接口

统一返回 `{ code, msg, data }`，`code === "200"` 才算成功：

| 接口 | 用途 |
|------|------|
| `GET /personal/report/categories` | 分类 → 文件 → sheet 树，文件节点含 `uploadId`、`origName`、`filePath` |
| `GET /personal/config` | `workspaceRoot`，Electron 拼本地绝对路径用 |
| `GET /personal/file?uploadId=...` | 按 uploadId 流式下载 Excel（浏览器形态） |
| `POST /personal/chat/stream` | SSE，必须用 POST + fetch ReadableStream，不能用 EventSource |
| `POST /personal/upload` | multipart，字段名 `excel`，可多文件 |

报表页点文件名：有 Electron 桥时用系统应用打开本地文件；否则新开窗口走下载接口。
