# MieBot Platform

面向 QQ 机器人 API v2 的 Kotlin/JVM 21 接入库和模块化多机器人运行平台，包含框架模块宿主、可信 PF4J 机器人插件宿主与可靠消息队列。

## 当前实现

- JDK 21 / Kotlin 1.9.25 / Spring Boot 3.5.16 多模块工程；后端生产源码、测试、模块与插件模板均使用 Kotlin
- 框架核心开放 `qqbot-module-api`、`qqbot-module-spi`，启动前扫描 `/modules/*.jar`，严格校验描述符、版本和依赖图，再由 `qqbot-module-host` 管理生命周期和模块间服务
- 七个默认外置功能模块：`platform-admin`、`database-support`、`qqbot-runtime`、`plugin-support`、`operations`、`cluster-support`、`onebot11`
- 功能模块 JAR 可携带 Spring 自动配置、数据库迁移和编译后的同源 Web Component；模块目录、制品文件名和 SHA-256 由 `GET /api/modules` 提供
- 稳定领域模型与 QQ 协议 DTO 分离
- Access Token 与完整 QQ OpenAPI 客户端：消息/撤回、频道/成员/身份组/权限、禁言、表态、公告、精华、日程、论坛、音频、互动回执和机器人分享链接
- 默认启用的真实 QQ Gateway 多机器人运行时
- 仅供外部第三方程序接入的 OneBot 11 C2C/普通群兼容模块，支持正向与反向 Universal WebSocket，并可在每个机器人编辑页独立配置；它不属于机器人插件开发接口
- SQLite/MySQL/PostgreSQL 持久化、版本化迁移和安全热切换
- Angular 22 管理后台工程
- 插件 SDK API 级别 `3.1.0`（Maven 制品 `1.0.1`，API、SPI、testkit）、可复制项目模板和本地分发任务
- Gateway Dispatch 持久化到 `event_inbox`，并提供管理员 Inbox 查询 API
- Outbox/DLQ 持久化状态、生产 QQ OpenAPI 发送、按插件绑定隔离的真实 QQ 消息回执查询、管理员 API 与后台实时视图
- PF4J 可信插件宿主、每机器人绑定、配置 Schema 和默认配置校验、绑定级 `PluginStorage`、暂停/恢复、超时取消与隔离、插件投递重试和插件 DLQ
- 每个机器人/插件独立的 `/data/plugin-data/<botId>/<pluginId>/` 数据目录，文件化 `config.json`，以及 SQLite、图片、音视频等插件自有文件
- PluginScheduler、无宿主策略限制的 PluginHttpClient、MediaService、EventService 多 handler 和绑定 ConfigSnapshot
- 插件页可信 JAR 选择/上传、校验、同进程无重启热升级、失败回滚和绑定文件管理
- 管理员改密、机器人删除（含二次确认）、变更审计日志和运行状态 SSE
- SQL bot/shard 租约与 fencing token；Inbox、插件投递和 Outbox 只由机器人租约持有者领取，插件哈希变化也会使不匹配实例失去续租资格
- 文本、Markdown、Keyboard、Ark、Embed，以及受大小/类型/SSRF 策略保护的本地或远程媒体 Outbox

`settings.gradle.kts` 当前声明 23 个 Gradle 子项目。它们包含模块 SDK/宿主、领域与协议库、QQ 运行时、插件 SDK/宿主、持久化、管理 API、示例插件和启动器；其中只有 `platform-admin`、`database-support`、`qqbot-runtime`、`plugin-support`、`operations`、`cluster-support`、`onebot11` 七个框架功能模块会作为默认独立 JAR 分发到运行时 `/modules` 目录。

应用会直接通过 HTTP 提供管理 API 和编译后的 Angular 静态资源。Caddy 或 Nginx 仅作为可选的生产反向代理。

SQLite 默认写入工作目录的 `qqbot.db`，并强制启用 WAL、外键、5 秒 busy timeout 和进程级文件锁；文件锁会随 Web 数据库热切换转移。管理后台的“系统”页面可测试并切换 SQLite、MySQL 和 PostgreSQL；切换失败时继续使用原数据库，空目标库会先初始化 schema 并复制当前唯一管理员。业务数据不会在数据库之间迁移。SQLite 只支持一个应用实例，多实例必须使用 MySQL/PostgreSQL。

QQ Gateway 默认启用。应用启动后会读取当前数据库中的机器人配置，自动为所有 `enabled=true` 的机器人创建彼此隔离的运行时，并按默认 15 秒周期持续调和配置。生产环境连接依次执行 Access Token 获取、`GET /gateway/bot` 接入点发现、连接返回的 WSS 地址、发送 Identify/Resume，收到 `READY` 后才记为在线。Web 首次设置和新建机器人表单默认 Intents 为 `33554432`（`2^25`，群聊与单聊消息事件）；实际可用事件仍取决于 QQ 开放平台为该机器人批准的权限。

管理员可通过 `GET /api/bots/runtime` 或 Dashboard 查看已启用数、当前在线数、连接阶段、心跳、事件序号、重连次数和脱敏错误。Dashboard 的“平台服务正常”表示应用及数据库就绪，不等同于 QQ Gateway 已连接；“运行机器人”的已连接数仅统计已进入 `ONLINE` 的运行时。

普通 QQ Gateway Dispatch 会先保留事件类型和原始 JSON，同步写入 `event_inbox` 并按环境、机器人、事件类型和平台事件 ID 去重；写入成功后才推进 Resume 序号。已知的消息、用户/群生命周期、频道/成员、表态、审核、论坛、音频和互动事件可通过 `GatewayDispatch.decodeKnownEvent()` 解码为强类型 DTO，未知事件继续以原始 Payload 向前兼容。稳定插件事件会直接提供被引用消息的 QQ ID，以及普通群消息发送者的 `member/admin/owner` 角色。管理员登录后可通过 `GET /api/events/inbox` 分页、筛选和搜索事件，通过 `GET /api/events/inbox/{id}` 查看受限长度的原始 Payload。`outbox_jobs` 已提供创建、租约领取、重试、成功、结果未知和死信状态转换；生产 Outbox worker 会按机器人隔离凭据调用 QQ OpenAPI，成功时原子保存真实 QQ 消息 ID、序号和平台时间，并将 429/5xx 重试、永久错误死信化。插件和后台产生的消息先写入 Outbox，再由 worker 发送；两者都可同时携带 `msg_id`/`event_id` 被动回复信息和显式 `message_reference`，插件还可按绑定持久化查询回执，后台 Outbox 详情显示相同字段。

`onebot11` 模块只供外部第三方程序通过 WebSocket 接入，只转换 QQ 官方 C2C 与普通群的可等价能力，不转换 QQ 频道，也不参与本项目的 PF4J 机器人插件开发。它支持正向 `/api`、`/event`、`/` WebSocket 和反向 Universal WebSocket；access token 为必填密文配置。OneBot 数字用户/群/消息 ID 是基于 QQ OpenID 和官方消息 ID 的数据库持久化别名，不是真实 QQ 号。支持的 action、事件、消息段、明确返回 `1404` 的范围及 Docker 端口要求见 [ONEBOT11.md](./ONEBOT11.md)。

机器人编辑页可设置每个机器人的最大媒体上传大小，默认 `16 MiB`、可选 `1-256 MiB`；前端选择文件、上传 API、插件 SDK、入队和发送 worker 都会再次校验。机器人页的发送入口支持文本、四类富消息、本地媒体、远程 HTTPS 媒体和可选的显式引用消息 ID。本地媒体暂存在 `QQBOT_MEDIA_STAGING_DIRECTORY`，成功、结果未知或死信后删除；远程媒体会先在服务端按 HTTPS、DNS/私网地址、重定向、超时和机器人大小上限受控下载，再交给 QQ。C2C/群聊使用 QQ `file_data` 预上传，频道/私信只支持图片并使用 multipart `file_image`。

后台“插件”页属于 `plugin-support` 框架模块，通过 `GET /api/plugins` 扫描挂载的 `/plugins` JAR，读取 manifest、大小、修改时间和 SHA-256，并显示宿主加载状态。可信 JAR 通过 PF4J 加载，使用 `ServiceLoader` 发现 Kotlin `BotPluginFactory` 实现；每个 JAR 必须通过 `Plugin-Default-Config` 声明一个符合 Schema 的默认 JSON 对象。页面可选择 `.jar` 并通过 `POST /api/plugins/upload` 上传，服务端完成校验后在当前进程内热升级，不需要应用重启，失败会尝试恢复旧插件。

“机器人绑定”先按机器人显示信息、Gateway 状态、插件数和更新时间，进入编辑页后每个插件使用独立栏目，只显示插件名、右侧删除操作和文件管理器。一个插件可绑定多个机器人，但每个绑定都使用 `/data/plugin-data/<botId>/<pluginId>/` 独立目录；`config.json` 只保存在该目录，不写入主数据库。新建绑定时 Web 表单加载制品的默认配置供管理员确认，保存后才创建目录和 `config.json`。文件管理器可浏览、新建、上传、下载和递归删除目录内文件，点击 JSON 文件会打开带 SHA-256 冲突检测的编辑器；同名上传默认返回冲突，Web 只有在管理员明确确认后才请求覆盖。删除插件绑定会永久删除整个绑定目录及其中的 SQLite、媒体和其他文件。多实例部署进行文件写入、解绑或删除机器人前，必须先把目标机器人和管理请求收敛到单个实例，或停掉其他副本并确认远端插件已释放文件句柄；默认单实例部署不需要额外操作。

`GET/POST/PUT/DELETE /api/plugin-bindings` 管理绑定和启停，`/api/plugin-bindings/{bindingId}/files` 下的接口管理绑定文件。停用绑定后待处理投递进入 `PAUSED`，重新启用后恢复；执行超时会先发送取消信号，宽限期内仍未停止的绑定进入 `QUARANTINED`，插件页可人工恢复。插件声明 capability 后可使用绑定级存储、调度器、本地/远程媒体、富消息和多 handler 事件订阅；HTTP Client 始终提供且不施加 URL、网络地址、请求头、重定向、请求/响应大小或最长超时策略。机器人插件是 `plugin-support` 加载的业务实现，不是框架功能模块。

后台功能复核结果：登录/首次设置、改密、机器人配置/启停/删除、数据库测试与热切换、Inbox、Outbox、两类 DLQ、Dashboard 指标、插件制品/绑定/投递、审计查询和顶部健康状态均读取真实 API。运行状态同时提供 `GET /api/bots/runtime` 和同源 `GET /api/bots/runtime/stream` SSE；浏览器不支持 SSE 时后台保留轮询兜底。`/login` 等后台路由支持直接刷新，应用壳会在认证后主动读取当前数据库配置。

插件宿主只允许管理员安装可信 JAR，PF4J 类加载和每绑定目录划分都不是安全沙箱；插件与宿主运行在同一 JVM，恶意插件可以尝试访问进程身份有权访问的其他文件、网络和资源。插件 API 不直接提供 Spring、主数据库连接、AppSecret 或 Access Token。插件配置按 manifest Schema 校验，异常和可取消超时按有限次数重试，无法在取消宽限期停止的执行会隔离整个绑定。

镜像内置 `example` 示例插件制品；源码 Compose 使用绑定目录时，`stageRuntimeExtensions` 会把它复制到 `./plugins/qqbot-plugin-example.jar`。在插件页将它绑定到机器人时，弹窗会载入插件 `config.json` 的 `triggerKeyword` 和 `replyContent` 预设，管理员可为每个机器人分别修改。默认收到 `/example` 后，会通过真实 Inbox -> 插件投递 -> Outbox -> QQ OpenAPI 链路回复 `example reply`。

Gateway、Token、OpenAPI、WSS 和恢复会话流程已有自动化测试；其中 Token/OpenAPI 使用模拟 HTTP 端点，WSS 使用 WebSocket transport 与协议测试替身。当前开发环境没有使用真实 QQ AppID/AppSecret 完成线上连接验收。因此文档中的“可运行”和“已实现”不代表真实账号、权限、配额及网络环境已经验证成功；部署后应以运行状态 API、Dashboard 和 QQ 开放平台侧状态为准。

项目已提供 `Dockerfile` 和 `compose.yaml`。镜像构建会从 Dragonwell 官方 GitHub Release 下载固定版本的 Dragonwell 21 压缩包，校验 SHA-256 后在 Linux 阶段解压，运行容器直接使用该 JDK，不依赖宿主机 Java。Compose 把 `./modules` 只读挂载到 `/modules`，把 `./plugins` 挂载到 `/plugins`，并将 SQLite、媒体暂存、插件绑定数据和运行数据放入 `/data` 持久卷；插件绑定数据默认位于 `/data/plugin-data`。首次启动会在持久化配置目录自动生成 AppSecret 加密主密钥，无需预先创建 Docker Secret。第一次访问 Web 后台会依次引导创建管理员、验证并选择数据库、配置首个 QQ 机器人；MySQL/PostgreSQL 模式可在完成前继续添加多个机器人。Debian 部署、外部数据库和候选配置文件的完整说明见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 本地构建

项目提供 Gradle Wrapper。Windows 下可以通过指定的 Java 21 直接启动 Wrapper：

```powershell
Set-Location .\qqbot-admin-web
npm.cmd ci
npm.cmd run build
Set-Location ..

& 'E:\JAVA\dragonwell-21.0.11.0.11+10-GA\bin\java.exe' `
  -classpath '.\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain clean test defaultModuleDirectory
```

前端使用独立 npm 工程：

Angular CLI 22.0.7 支持 Node.js `^22.22.3`、`^24.15.0` 或 `>=26.0.0`；本项目 Docker 构建使用 Node 24.15.0。安装依赖后可使用符合版本要求的 `node.exe` 直接调用项目内 CLI，不需要修改系统 PATH：

```powershell
Set-Location .\qqbot-admin-web
npm.cmd ci
& 'C:\path\to\node.exe' '.\node_modules\@angular\cli\bin\ng.js' test qqbot-admin-web --watch=false
npm.cmd run build
```

前端构建会同时生成后台壳和五个带 Web Component 页面贡献的模块页面包：`operations`、`qqbot-runtime`、`plugin-support`、`platform-admin`、`onebot11`。这五个页面包不等于默认框架模块总数；默认 `/modules` 分发仍包含七个模块，`database-support` 和 `cluster-support` 当前没有单独的前端页面包。随后执行 `:qqbot-app:bootJar defaultModuleDirectory`：壳资源进入核心 Boot JAR，模块页面进入各自的普通 JAR，默认模块输出到 `build/runtime/modules`。`stageRuntimeExtensions` 可把默认模块和示例插件放入项目的 `modules/`、`plugins/` 目录供 Compose 使用。

框架功能开发者应阅读 [MODULE_DEVELOPMENT.md](./MODULE_DEVELOPMENT.md)；文档说明模块/插件边界、依赖图、生命周期、模块间服务、Web Component 和 Docker 接入，`moduleSdkRepository`/`moduleSdkDistribution` 可生成模块 SDK。OneBot 接入和兼容边界见 [ONEBOT11.md](./ONEBOT11.md)。插件作者应阅读 [PLUGIN_DEVELOPMENT.md](./PLUGIN_DEVELOPMENT.md)，并可直接复制 [plugin-template](./plugin-template)；`pluginSdkRepository`/`pluginSdkDistribution` 可生成插件 SDK。完整平台需求见 [REQUIREMENTS.md](./REQUIREMENTS.md)，发行变更见 [CHANGELOG.md](./CHANGELOG.md)。
