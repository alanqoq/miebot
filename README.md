# Mirai QQ Bot Platform

面向 QQ 机器人 API v2 的 Java 接入库和多机器人运行平台，包含可信 PF4J 插件宿主与可靠消息队列。

## 当前实现

- Java 21 / Spring Boot 3.5 多模块工程
- 稳定领域模型与 QQ 协议 DTO 分离
- Access Token 与 OpenAPI 客户端基础
- 默认启用的真实 QQ Gateway 多机器人运行时
- SQLite/MySQL/PostgreSQL 持久化、版本化迁移和安全热切换
- Angular 22 管理后台工程
- 插件 SDK 兼容级别 `1.2.0`（Maven 制品 `0.2.0`，API、SPI、testkit）、可复制项目模板和本地分发任务
- Gateway Dispatch 持久化到 `event_inbox`，并提供管理员 Inbox 查询 API
- Outbox/DLQ 持久化状态、生产 QQ OpenAPI 发送、管理员查询 API 与后台实时视图
- PF4J 可信插件宿主、每机器人绑定、配置 Schema 校验、绑定级 `PluginStorage`、暂停/恢复、超时取消与隔离、插件投递重试和插件 DLQ
- PluginScheduler、受限 HTTP、MediaService、EventService 多 handler 和绑定 ConfigSnapshot
- 插件页可信 JAR 选择/上传、校验、同进程无重启热升级和失败回滚
- 管理员改密、机器人删除（含二次确认）、变更审计日志和运行状态 SSE
- SQL bot/shard 租约与 fencing token；Inbox、插件投递和 Outbox 只由机器人租约持有者领取，插件哈希变化也会使不匹配实例失去续租资格
- 文本、Markdown、Keyboard、Ark、Embed，以及受大小/类型/SSRF 策略保护的本地或远程媒体 Outbox

应用会直接通过 HTTP 提供管理 API 和编译后的 Angular 静态资源。Caddy 或 Nginx 仅作为可选的生产反向代理。

SQLite 默认写入工作目录的 `qqbot.db`，并强制启用 WAL、外键、5 秒 busy timeout 和进程级文件锁；文件锁会随 Web 数据库热切换转移。管理后台的“系统”页面可测试并切换 SQLite、MySQL 和 PostgreSQL；切换失败时继续使用原数据库，空目标库会先初始化 schema 并复制当前唯一管理员。业务数据不会在数据库之间迁移。SQLite 只支持一个应用实例，多实例必须使用 MySQL/PostgreSQL。

QQ Gateway 默认启用。应用启动后会读取当前数据库中的机器人配置，自动为所有 `enabled=true` 的机器人创建彼此隔离的运行时，并按默认 15 秒周期持续调和配置。生产环境连接依次执行 Access Token 获取、`GET /gateway/bot` 接入点发现、连接返回的 WSS 地址、发送 Identify/Resume，收到 `READY` 后才记为在线。Web 首次设置和新建机器人表单默认 Intents 为 `33554432`（`2^25`，群聊与单聊消息事件）；实际可用事件仍取决于 QQ 开放平台为该机器人批准的权限。

管理员可通过 `GET /api/bots/runtime` 或 Dashboard 查看已启用数、当前在线数、连接阶段、心跳、事件序号、重连次数和脱敏错误。Dashboard 的“平台服务正常”表示应用及数据库就绪，不等同于 QQ Gateway 已连接；“运行机器人”的已连接数仅统计已进入 `ONLINE` 的运行时。

普通 QQ Gateway Dispatch 会先保留事件类型和原始 JSON，同步写入 `event_inbox` 并按环境、机器人、事件类型和平台事件 ID 去重；写入成功后才推进 Resume 序号。管理员登录后可通过 `GET /api/events/inbox` 分页、筛选和搜索事件，通过 `GET /api/events/inbox/{id}` 查看受限长度的原始 Payload。`outbox_jobs` 已提供创建、租约领取、重试、成功、结果未知和死信状态转换；生产 Outbox worker 会按机器人隔离凭据调用 QQ OpenAPI，并将 429/5xx 重试、永久错误死信化。插件产生的消息先写入 Outbox，再由 worker 发送，避免插件线程直接接触网络或密钥。

机器人编辑页可设置每个机器人的最大媒体上传大小，默认 `16 MiB`、可选 `1-256 MiB`；前端选择文件、上传 API、插件 SDK、入队和发送 worker 都会再次校验。机器人页的发送入口支持文本、四类富消息、本地媒体和远程 HTTPS 媒体。本地媒体暂存在 `QQBOT_MEDIA_STAGING_DIRECTORY`，成功、结果未知或死信后删除；远程媒体会先在服务端按 HTTPS、DNS/私网地址、重定向、超时和机器人大小上限受控下载，再交给 QQ。C2C/群聊使用 QQ `file_data` 预上传，频道/私信只支持图片并使用 multipart `file_image`。

后台“插件”页通过 `GET /api/plugins` 扫描挂载的 `/plugins` JAR，读取 manifest、大小、修改时间和 SHA-256，并显示宿主加载状态。可信 JAR 通过 PF4J 加载，使用 `ServiceLoader` 暴露纯 Java `BotPluginFactory` 或 V2 工厂；页面可选择 `.jar` 并通过 `POST /api/plugins/upload` 上传，服务端完成校验后在当前进程内热升级，不需要应用重启，失败会尝试恢复旧插件。`GET/POST/PUT/DELETE /api/plugin-bindings` 管理每个机器人独立配置。停用绑定后待处理投递进入 `PAUSED`，重新启用后恢复；执行超时会先发送取消信号，宽限期内仍未停止的绑定进入 `QUARANTINED`，插件页可人工恢复。插件声明 capability 后可使用绑定级存储、调度器、受限 HTTP、本地/远程媒体、富消息和多 handler 事件订阅；未声明能力的插件会收到拒绝实现。

后台功能复核结果：登录/首次设置、改密、机器人配置/启停/删除、数据库测试与热切换、Inbox、Outbox、两类 DLQ、Dashboard 指标、插件制品/绑定/投递、审计查询和顶部健康状态均读取真实 API。运行状态同时提供 `GET /api/bots/runtime` 和同源 `GET /api/bots/runtime/stream` SSE；浏览器不支持 SSE 时后台保留轮询兜底。`/login` 等后台路由支持直接刷新，应用壳会在认证后主动读取当前数据库配置。

插件宿主只允许管理员安装可信 JAR，PF4J 类加载不是安全沙箱；插件 API 不暴露 Spring、数据库、AppSecret 或 Access Token。插件配置按 manifest Schema 校验，异常和可取消超时按有限次数重试，无法在取消宽限期停止的执行会隔离整个绑定。

镜像内置 `echo` 示例插件制品，新建插件卷时会复制到 `/plugins/qqbot-plugin-echo.jar`。在插件页将它绑定到机器人后，收到 `/ping` 会通过真实 Inbox -> 插件投递 -> Outbox -> QQ OpenAPI 链路回复 `pong`；`/remember` 会写入该绑定自己的存储空间，用于验证多机器人数据隔离。

Gateway、Token、OpenAPI、WSS 和恢复会话流程已有自动化测试；其中 Token/OpenAPI 使用模拟 HTTP 端点，WSS 使用 WebSocket transport 与协议测试替身。当前开发环境没有使用真实 QQ AppID/AppSecret 完成线上连接验收。因此文档中的“可运行”和“已实现”不代表真实账号、权限、配额及网络环境已经验证成功；部署后应以运行状态 API、Dashboard 和 QQ 开放平台侧状态为准。

项目已提供 `Dockerfile` 和 `compose.yaml`。镜像构建会从 Dragonwell 官方 GitHub Release 下载固定版本的 Dragonwell 21 压缩包，校验 SHA-256 后在 Linux 阶段解压，运行容器直接使用该 JDK，不依赖宿主机 Java。Compose 将 SQLite、媒体暂存和运行数据放入 `/data` 持久卷。首次启动会在持久化配置目录自动生成 AppSecret 加密主密钥，无需预先创建 Docker Secret。第一次访问 Web 后台会依次引导创建管理员、验证并选择数据库、配置首个 QQ 机器人；MySQL/PostgreSQL 模式可在完成前继续添加多个机器人。Debian 部署、外部数据库和候选配置文件的完整说明见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 本地构建

项目提供 Gradle Wrapper。Windows 下可以通过指定的 Java 21 直接启动 Wrapper：

```powershell
& 'E:\JAVA\dragonwell-21.0.11.0.11+10-GA\bin\java.exe' `
  -classpath '.\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain clean test
```

前端使用独立 npm 工程：

Angular 22.0.7 要求 Node.js `24.15.0+`（或官方支持的其他版本）。安装依赖后可使用符合版本要求的 `node.exe` 直接调用项目内 CLI，不需要修改系统 PATH：

```powershell
Set-Location .\qqbot-admin-web
npm.cmd ci
& 'C:\path\to\node.exe' '.\node_modules\@angular\cli\bin\ng.js' test --watch=false
& 'C:\path\to\node.exe' '.\node_modules\@angular\cli\bin\ng.js' build
```

前端构建完成后，重新执行 `:qqbot-app:bootJar` 会把 `dist` 静态资源打入可执行 JAR。应用默认监听 `8080`；本机端口被占用时可追加 `--server.port=18080`。

插件作者应先阅读 [PLUGIN_DEVELOPMENT.md](./PLUGIN_DEVELOPMENT.md)，并可直接复制 [plugin-template](./plugin-template)；文档包含当前可用能力、Manifest、Schema、消息发送、绑定存储、测试、网页上传和热升级说明。运行 `pluginSdkRepository` 或 `pluginSdkDistribution` 可生成 SDK 分发物。完整平台需求见 [REQUIREMENTS.md](./REQUIREMENTS.md)。
