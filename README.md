# InsightLens

> 把 Excel 丢进去，用中文问数。

InsightLens 是一个**本地运行的桌面版数据分析助手**。上传一份 Excel（订单、流水、明细都行），它会自动建库建表，
然后你可以直接用自然语言提问——「上个月各品类退款率是多少」「按渠道看客单价趋势」——它会生成 SQL、校验、执行，
再把结果和图表一起给你。

不需要连数据库、不需要写 SQL、不需要把数据传到别人的服务器：数据库是本地 DuckDB 文件，数据始终在自己电脑上。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能特性](#功能特性)
- [下载安装](#下载安装)
- [首次配置](#首次配置)
- [使用说明](#使用说明)
- [数据存在哪](#数据存在哪)
- [卸载与更新](#卸载与更新)
- [技术栈与架构](#技术栈与架构)
- [本地开发](#本地开发)
- [打包发布](#打包发布)
- [常见问题](#常见问题)

---

## 它解决什么问题

运营或业务同学要看数据，通常卡在三个地方：

1. **等不到**——临时需求排在排期表后面，会前两小时提的需求，会后才给结果；
2. **不会写**——BI 工具要拖字段、写计算字段，口径一复杂就得上 SQL；
3. **口径乱**——手工在 Excel 里拼透视表，退款率算的是「退款单数/订单数」还是「退款金额/支付金额」，两个人能算出两个数。

InsightLens 把这条链路压到一次对话：**上传表格 → 用中文问 → 拿到带 SQL 的依据**。
每个答案都能展开看它生成的 SQL，口径不再是黑盒。

## 功能特性

- **Excel 一键建库**：支持 `.xlsx` / `.xls`，自动识别表头、推断列类型，多文件多 sheet 各自成表；重复上传会重建，不产生脏表。
- **自然语言问数**：七节点状态机（澄清 → 召回表结构 → 生成 SQL → 安全校验 → 执行 → 总结 → 自检）驱动，问得不清楚会先追问，而不是硬猜。
- **SQL 安全守卫**：只允许单条 `SELECT`；表名必须来自当前工作区的白名单；拦截 `DROP/DELETE/UPDATE/INSERT/ATTACH/COPY` 等危险关键词；自动补行数上限与超时。
- **答案可追溯**：每条回答附生成的 SQL、命中的表与列、执行耗时，方便复核口径。
- **报表视图**：按「分类 → 文件 → sheet」组织数据源，点进去能看到该数据源下的对话与产出。
- **多模型可切换**：设置页选主模型，OpenAI 兼容接口（任意中转站）与 DeepSeek 官方二选一，Key 各自保存，随时切。
- **离线优先**：核心链路不依赖外网服务，只有调用大模型时联网；联网搜索是可选增强项，默认关闭。
- **自动更新**：启动后检查 GitHub Releases，有新版本会询问，不会静默重启（避免打断正在跑的查询）。

## 下载安装

### 系统要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10 / 11（64 位） |
| 内存 | 建议 4 GB 以上（应用自身约占用 1 GB 堆） |
| 磁盘 | 安装包约 240 MB，装完约占 600 MB |
| **Java** | **必须安装 JDK 17 或更高版本，并加入 `PATH`** |

> 为什么还要装 Java：应用主体是 Spring Boot 后端，为控制安装包体积**没有内置 JRE**，启动时由桌面壳调用系统 `java`。
> 安装后可在命令行执行 `java -version` 验证，输出 `17.x` 及以上即可。
> 没有 Java 时，启动会弹出「后端启动失败：请确认已安装 JDK 17 并在 PATH 中」。
> 下载地址：<https://adoptium.net/temurin/releases/?version=17>

### 下载步骤

1. 打开 Releases 页面：<https://github.com/huanghaiwei123/InsightLens/releases/latest>
2. 在 **Assets** 里下载 `InsightLens-Setup-x.y.z.exe`（其余 `*.blockmap` / `latest.yml` 是自动更新用的，手动安装不需要）。
3. 双击运行安装包。
4. Windows 可能提示「已保护你的电脑」——因为安装包**没有代码签名证书**。点 **更多信息 → 仍要运行** 即可。
5. 选择安装目录（默认当前用户目录，无需管理员权限），一路下一步，完成后桌面会出现 InsightLens 图标。

### 从源码构建（可选）

见下方 [本地开发](#本地开发) 与 [打包发布](#打包发布)。

## 首次配置

应用启动后打开 **设置 → 主模型**，二选一填 Key：

| 选项 | 适用场景 | 需要填什么 |
|---|---|---|
| **OpenAI 兼容** | 有任意 OpenAI 协议的中转站 / 官方 Key | API Key（接口地址与模型名已预置，可在配置文件里改） |
| **DeepSeek** | 只有 DeepSeek 官方 Key | API Key（默认模型 `deepseek-chat`，接口 `https://api.deepseek.com`） |

- 两家可以都填，随时点「设为主模型」切换，**保存即生效，不用重启**。
- 未填 Key 的那张卡片按钮是灰的——不允许切到一个必然失败的状态。
- 选择结果会写入本地配置，下次启动仍是上次选的那家。

> Key 只保存在本机 `%APPDATA%\insightlens-frontend\application-local.yaml`，不会上传到任何地方。
> 如果这是别人的电脑，用完记得在设置页清空。

### 换成其它服务商 / 中转站

安装包里预置的是 **OpenAI 官方**地址（`https://api.openai.com`，模型 `gpt-4o-mini`）。
如果你用的是 OpenAI 协议的中转站，改两行就行：

打开 `%APPDATA%\insightlens-frontend\application-local.yaml`，把

```yaml
    openai:
      base-url: https://api.openai.com    # 改成中转站地址，不要带 /v1
      chat:
        options:
          model: gpt-4o-mini              # 改成中转站里存在的模型名
```

保存后重启应用（Key 仍可在设置页里填）。`base-url` 千万别带 `/v1`——Spring AI 会自己拼 `/v1/chat/completions`。

## 使用说明

### 1. 上传 Excel

进入 **上传文件** 页，把 Excel 拖进虚线框，或点「选择文件」。

- 支持一次选多个文件；
- 一个文件里的多个 sheet 会各建一张表；
- 第一行默认作为表头；
- 上传完成后可在「数据报表」页看到新建的分类与文件。

> 上传时请**关闭正在用 Excel / WPS 打开的那个文件**，否则 Windows 会锁住文件导致移动失败。
> 遇到这种情况应用会提示「文件被占用，请关闭后重试」，不影响已上传的其他文件。

### 2. 开始问数

进入 **对话** 页，直接用中文提问，例如：

```
上个月各品类的退款率是多少？
按渠道统计最近 30 天的支付金额和订单数
哪一天的销售额最高？
```

- 问题太模糊时它会先反问（比如「上个月」是哪个月、退款率按金额还是按单量）；
- 回答下方可展开查看完整 SQL 与执行信息；
- 表格结果支持排序、导出。

### 3. 看历史与报表

**数据报表** 页按 `分类 → 文件 → sheet` 展示数据源。点分类可以钻取，点文件名可以用系统默认程序打开原始 Excel。

## 数据存在哪

| 内容 | 路径 |
|---|---|
| 数据库（DuckDB 单文件） | `%APPDATA%\insightlens-frontend\data\insightlens.duckdb` |
| 上传的 Excel 工作副本 | `%APPDATA%\insightlens-frontend\data\excel\` |
| 本地配置（含 API Key） | `%APPDATA%\insightlens-frontend\application-local.yaml` |
| 后端运行日志 | `%APPDATA%\insightlens-frontend\backend.log` |

备份就是整目录复制；换电脑把 `data` 与 `application-local.yaml` 一起拷过去即可。

## 卸载与更新

- **卸载**：Windows 设置 → 应用 → InsightLens → 卸载。默认**保留**数据目录，重新装回来数据还在；要彻底清干净就手动删掉 `%APPDATA%\insightlens-frontend`。
- **更新**：应用启动 5 秒后检查 GitHub Releases，发现新版本会询问是否下载；下载完成后再次询问是否立即安装。
  升级只替换程序，`%APPDATA%` 下的数据和配置不动。

## 技术栈与架构

```
┌───────────────────────────── Electron 35（桌面壳）─────────────────────────────┐
│  启动时挑空闲端口 → spawn java（Spring Boot）→ 等 /personal/config 就绪 → 加载页面  │
│  自动更新：electron-updater + GitHub Releases                                    │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ HTTP / SSE
┌───────────────────────────────────▼───────────────────────────────────────────┐
│ Vue 3 + Vite（概览 / 报表 / 对话 / 上传 / 设置），由后端同源托管                 │
└───────────────────────────────────┬───────────────────────────────────────────┘
┌───────────────────────────────────▼───────────────────────────────────────────┐
│ Spring Boot 3.5 + Spring AI 1.1.2（OpenAI / DeepSeek 可切）                     │
│                                                                               │
│  Clarify → SchemaRetrieve → SqlGenerate → SqlGuard → SqlExecute → Summarize    │
│      ▲                                                      │                  │
│      └──────────────── Judge（自检不通过则回到生成，最多 N 轮）◄─┘              │
│                                                                               │
│  MyBatis（元数据） + DuckDB（分析库，只读打开 + 表名白名单）                      │
└───────────────────────────────────────────────────────────────────────────────┘
```

**为什么用状态机而不是一个 ReAct 循环**：问数是个有明确阶段的流水线，每一步的失败模式不同
（召回不到表 / SQL 写错 / 被安全规则拦下 / 执行超时），拆开后每步都能单独校验、单独记录、单独限制次数；
一个 ReAct 循环把这些问题糊在一起，出错只能整轮重来，也没法保证「绝不出写操作」。

**安全边界**：DuckDB 以只读方式打开，SQL 守卫做语法级校验（JSqlParser 解析 + 表名白名单 + 关键词黑名单 + 强制 LIMIT），
再叠加数据库账号层权限，形成两层防线。

| 层 | 技术 |
|---|---|
| 桌面壳 | Electron 35、electron-builder、electron-updater |
| 前端 | Vue 3、Vite 6、Vue Router（hash 模式） |
| 后端 | Spring Boot 3.5、Spring AI 1.1.2（OpenAI / DeepSeek starter） |
| 分析库 | DuckDB（嵌入式，单文件） |
| 元数据 | DuckDB + MyBatis |
| 构建 | Maven、npm |

## 本地开发

### 环境

- JDK 17+
- Maven 3.9+（或直接用仓库自带的 `mvnw`）
- Node.js 18+

### 跑起来

```bash
# 1. 后端配置：由模板复制一份本地配置（含密钥，已被 .gitignore 忽略）
cp application-local.example.yaml application-local.yaml
#    然后编辑 application-local.yaml，填入 api-key

# 2. 构建前端到后端静态目录
cd frontend && npm install && npm run build && cd ..

# 3. 启动后端（默认 http://localhost:8080）
./mvnw spring-boot:run
```

浏览器打开 <http://localhost:8080/> 即可，前端已由后端同源托管。

只想改前端时：

```bash
cd frontend
npm run dev     # http://localhost:5173，接口自动代理到 8080
```

只想跑桌面壳（走解包形态，比 `-jar` 快约 3 秒）：

```bash
cd frontend
npm run electron
```

### 目录结构

```
├── src/main/java/com/gdou/agent/
│   ├── api/                # Controller：报表、上传、对话（SSE）、设置
│   ├── orchestration/      # 状态机：GraphRunner + 7 个 Node
│   ├── infra/              # DuckDB、SQL 守卫、文件工具、Key 管理、MCP 守卫
│   └── react/              # 联网搜索用的 ReAct 执行器（可选能力）
├── src/main/resources/
│   ├── message/            # 各节点提示词模板
│   ├── static/             # 前端构建产物（随源码提交，保证打包一致）
│   └── application.yaml
├── frontend/
│   ├── electron/           # 主进程、预加载、自动更新
│   ├── src/                # Vue 页面与接口封装
│   └── scripts/            # 打包与运行时准备脚本
└── design/ eval/           # 状态机、工具契约、评测集（语言无关，换域复用）
```

## 打包发布

```bash
# 1. 先出后端产物：electron-builder 会把 target/classes 与 target/lib 打进安装包
./mvnw clean package -DskipTests

# 2. 再出前端 + 安装包（会自动先跑 vite build）
cd frontend
npm run dist
```

产物在 `frontend/release/`，需要上传到 GitHub Release 的是三个文件：

| 文件 | 作用 |
|---|---|
| `InsightLens-Setup-x.y.z.exe` | 安装包本体 |
| `InsightLens-Setup-x.y.z.exe.blockmap` | 差分更新用，漏了会导致每次全量下载 |
| `latest.yml` | 版本清单，自动更新靠它判断有没有新版本 |

> `frontend/release/` 已被 `.gitignore` 忽略。安装包走 Release 附件，不进仓库。

发版顺序（顺序错了会出现「更新检查失败」或「装完还是旧代码」）：

1. 改 `frontend/package.json` 的 `version`；
2. `./mvnw clean package` → `cd frontend && npm run dist`；
3. 提交并打 tag：`git tag v0.1.0 && git push origin v0.1.0`；
4. GitHub 上新建 Release，选该 tag，上传上面三个文件；
5. 老版本用户下次启动即可收到更新提示。

## 常见问题

**Q：双击图标后白屏 / 一直转圈？**
先看 `%APPDATA%\insightlens-frontend\backend.log`。绝大多数是这两类：
① 没装 JDK 17，或装了但没进 `PATH`（命令行 `java -version` 验证）；
② 安装包不完整——重装一次。

**Q：提示「后端启动失败」但 Java 明明装了？**
`PATH` 里放的是 JRE 而不是 JDK，或存在多个 Java 版本指向了 8/11。用 `where java` 看实际命中的是哪一个。

**Q：上传 Excel 报「文件被占用」？**
该文件正被 Excel / WPS 打开。关掉再传。判断依据是目录里出现 `~$` 开头的临时锁文件。

**Q：回答里的数字和我在 Excel 里算的不一样？**
先展开看它生成的 SQL。常见差异是口径：是否排除退款单、时间按支付时间还是下单时间、去重按订单还是按明细行。
可以直接追问「退款率按退款金额除以支付金额算」来纠正。

**Q：能处理多大的表？**
DuckDB 对列存很能扛，几十万行秒级。但 Excel 本身单表上限约 104 万行，超过请先转 CSV 或拆表。

**Q：会自动上传我的数据吗？**
不会。数据只在本机 DuckDB 文件里。只有你提问时，把**表结构与问题**发给大模型——具体数据行不会整表外传，
但如果问题里包含具体数值条件，这些条件会出现在发给模型的 SQL 草稿里。

**Q：为什么安装包没有代码签名？**
个人项目未购买证书，Windows SmartScreen 会提示未知发布者，选择「仍要运行」即可。

## 说明

个人学习项目，未附开源许可证，代码可读可参考，请勿直接商用。
问题反馈请提 Issue：<https://github.com/huanghaiwei123/InsightLens/issues>
