# InsightLens 前端（Vue 3 + Vite）

页面：概览、数据报表、对话、上传文件、设置。
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

## Electron 桌面版（开发态）

一键流程（在 `frontend/` 下执行）：

```bash
npm run build                         # 产出 ../src/main/resources/static（ jar 里要含最新页面）
cd .. && ./mvnw -q -DskipTests package   # 生成 target/InsightLens-0.0.1-SNAPSHOT.jar
cd frontend && npm run electron
```

主进程（`electron/main.cjs`）启动时会自动完成这些事，无需手工准备：

1. 建 `%APPDATA%/insightlens-frontend/data/excel` —— DuckDB 数据库和上传工作区都在这里；
2. 首次运行把项目根目录的 `application-local.yaml`（密钥）复制到 userData，已存在则不覆盖；
3. 从 8080 起挑一个空闲端口启动后端 jar（传 `-Dinsightlens.home=<userData>`），避开被占用的端口；
4. 等 `/personal/config` 就绪后加载页面；后端日志同时写进 `%APPDATA%/insightlens-frontend/backend.log`。

后端启动时会自动执行 `InsightLens.sql` 建表（脚本幂等），所以全新 userData 第一次打开就能用。

**密钥更新**：改了项目根目录的 `application-local.yaml` 后，需要手动删除 userData 里的同名文件，
下次启动才会重新复制（已有文件不覆盖，避免抹掉你在应用里改过的配置）。

**排障**：窗口白屏或连不上时，先看 `%APPDATA%/insightlens-frontend/backend.log`——
后端异常和渲染进程的 console 都记在这里。启动时报错（缺 jar / 缺密钥 / 端口耗尽 / 后端超时）会弹对话框说明原因。

`preload.cjs` 只暴露 `openFile` / `showItemInFolder`，且限制路径必须在 userData 内。
页面由后端同源托管，因此不需要 `VITE_API_BASE`。

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
