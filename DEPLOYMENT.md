# Debian Docker Compose 部署

## 部署结论

项目可以部署到 Debian 的 Docker Compose 中，当前 Compose 镜像为 `mirai-qqbot:1.0.0`。Compose 只运行 QQ Bot 应用，默认使用容器数据卷中的 SQLite；MySQL/PostgreSQL 由外部系统提供，通过 Web 后台或候选配置文件填写连接信息。Spring Boot 直接在 `8080` 端口提供管理 API 和 Angular 页面，不强制依赖 Caddy/Nginx。真实 QQ Gateway 运行时默认启用，应用启动后会自动调和当前数据库内所有已启用机器人。

镜像携带 `database-support`、`qqbot-runtime`、`platform-admin`、`plugin-support`、`operations`、`cluster-support` 和 `onebot11` 七个默认框架模块 JAR。Compose 将宿主机 `./modules` 只读挂载到 `/modules`，Spring Boot 使用 `PropertiesLauncher` 在启动前把其中的 JAR 加入类路径；宿主随后校验描述符、SHA-256、框架版本、必需依赖、版本下限、重复项和依赖环。`GET /api/modules` 可查看制品和运行状态。替换模块后只需重启应用，不需要重新编译核心；模块不能从 Web 上传或热卸载。`/plugins` 只存放由 `plugin-support` 加载并绑定机器人的业务插件，绑定配置和插件自有数据则持久化在 `/data/plugin-data`。

当前实现已通过本地单元、集成、模拟 HTTP 端点以及 WebSocket transport/协议测试，但本开发环境没有使用真实 QQ AppID/AppSecret 完成线上连接验收。能成功构建和启动容器只表示部署结构可用，不表示真实账号的凭据、Intents、Gateway 配额或外网策略已经通过 QQ 侧验证。

镜像构建会从 Dragonwell 官方 GitHub Release 下载固定版本的
`Alibaba_Dragonwell_Extended_21.0.11.0.11.10_x64_linux.tar.gz`。该文件已确认为 Linux x86_64、GNU libc 版本，SHA-256 为：

```text
12c642f8d6c6e0930b9b4e673d47822227ea46e7559c7b7b6b4c0331ace0580f
```

Docker 构建阶段会校验该摘要并在 Linux 文件系统中解压到 `/opt/dragonwell`，运行阶段直接执行 `/opt/dragonwell/bin/java`。构建主机必须能访问 `github.com` 和 `release-assets.githubusercontent.com`；不要先在 Windows 上解压该包，否则可能丢失符号链接和可执行权限。

## 前置条件

- Debian 12 amd64，或能够构建/运行 `linux/amd64` 镜像的 Docker 主机。
- Docker Engine 和 Docker Compose v2 插件。
- 首次构建时可访问 GitHub Releases、Debian、Node、Gradle Plugin Portal 和 Maven Central，用于拉取 JDK、基础镜像及构建依赖。
- 外部 MySQL 使用 8.0+；PostgreSQL 建议 15+。数据库账号需要在目标数据库内创建表、索引、约束和 Flyway 历史表，并具有正常的 CRUD 权限。
- 容器必须能解析公网 DNS，并通过 TCP 443 访问 QQ Token、OpenAPI 以及 QQ 动态返回的 WSS Gateway 地址；出站代理或 TLS 检查设备必须支持长连接和 WebSocket Upgrade。

确认环境：

```bash
docker version
docker compose version
uname -m
```

`uname -m` 应为 `x86_64`。在 arm64 主机上运行会依赖模拟，性能和兼容性不作为当前部署基线。

## 首次准备

在项目根目录先创建持久目录：

```bash
mkdir -p config modules plugins
sudo chown -R 10001:10001 config plugins
sudo chmod 0700 config
```

首次启动时，应用会自动生成 32 字节安全随机主密钥并以 Base64 写入 `config/app-secret.key`；Linux 文件系统上会尽力将权限设为 `0600`。该主密钥同时保护机器人 AppSecret 和写入活动数据库配置的 MySQL/PostgreSQL 密码。必须与数据库备份一起离线保存；丢失或替换密钥后，已有密文无法恢复。

如需由外部密钥管理系统提供主密钥，可在 `.env` 中设置 `QQBOT_MASTER_KEY=<32字节密钥的Base64>`。显式值非空时优先使用，应用不会读取或创建 `config/app-secret.key`。

构建镜像后，从镜像提取默认模块和示例插件到 Compose 的绑定目录，再启动：

```bash
docker compose build
sh ./scripts/stage-compose-extensions.sh
sudo chown -R 10001:10001 config plugins
docker compose up -d
docker compose ps
docker compose logs -f qqbot
```

默认访问地址：

```text
http://<Debian服务器IP>:8080/
```

第一次访问会自动进入首次设置向导：

1. 创建 Web 管理员。表单示例默认填入 `admin`，可修改；密码长度必须为 12-72 位。管理员用户名不是系统固定值，已有部署以数据库 `admin_users.username` 为准；当前实例实际用户名是 `alanqaq`，后续切换数据库时也应使用该账号。创建成功后当前浏览器会直接建立管理员会话。
2. 选择 SQLite、MySQL 或 PostgreSQL。SQLite 默认使用 `/data/qqbot.db`；外部数据库需要填写地址、端口、数据库名、用户名、密码及 SSL 模式。点击“验证并保存”后，应用会完成连接、schema、读取和回滚式写入检查；任一检查失败都会保留当前输入和原数据库，必须修正后才能继续。
3. 填写 QQ 开放平台机器人的 AppID 和 AppSecret。SQLite 在保存首个机器人后直接完成向导；MySQL/PostgreSQL 保存后可点击“继续添加”配置更多机器人，也可直接完成。

向导进度保存在 `config/onboarding.json`，浏览器刷新或容器重启后会恢复到未完成步骤。不要通过删除该文件来重置已有部署；对于已有管理员的旧版本部署，升级后首次生成状态文件时会自动视为已完成，避免重新触发向导。

端口和监听地址可通过根目录 `.env` 调整：

```dotenv
QQBOT_PUBLISHED_ADDRESS=0.0.0.0
QQBOT_PUBLISHED_PORT=8080
QQBOT_COOKIE_SECURE=false
QQBOT_GATEWAY_ENABLED=true
QQBOT_GATEWAY_SHUTDOWN_TIMEOUT=10s
```

直接使用 HTTP 时保持 `QQBOT_COOKIE_SECURE=false`。只有浏览器实际通过 HTTPS 访问时才设置为 `true`。Caddy/Nginx 可用于 TLS 和域名接入，但不是应用启动条件。

## OneBot 11 WebSocket

机器人编辑页的 OneBot 区域按机器人独立保存启用状态、正向监听地址/端口、反向 Universal WebSocket URL、access token、心跳和重连间隔。配置默认关闭；启用时 access token 和至少一种传输必填。反向端口写在完整的 `ws://` 或 `wss://` URL 中，因此没有单独端口字段。

反向 WebSocket 是容器主动出站连接，无需发布端口。URL 中 `127.0.0.1`/`localhost` 指应用容器；连接 Debian 宿主机服务可使用 `host.docker.internal`，连接其他主机应使用可路由 DNS/IP。跨主机建议使用 `wss://`。

正向 WebSocket 端口由数据库中的机器人配置决定，Compose 不会自动发布。需要把每个端口显式加入 `compose.yaml`，并在机器人页把监听地址从本机默认值 `127.0.0.1` 改为容器可访问的 `0.0.0.0`：

```yaml
ports:
  - "${QQBOT_PUBLISHED_ADDRESS:-0.0.0.0}:${QQBOT_PUBLISHED_PORT:-8080}:8080"
  - "5700:5700"
```

多个机器人必须使用不同正向端口，并逐个添加映射和防火墙规则。OneBot access token 不应与 QQ AppSecret 相同；不要将端口无保护暴露到公网。完整 action、事件、消息段和不支持范围见 [ONEBOT11.md](./ONEBOT11.md)。

## QQ Gateway 运行流程与网络

`QQBOT_GATEWAY_ENABLED` 默认为 `true`。Spring 应用完成基础设施启动后，Supervisor 会立即读取活动数据库中的机器人配置，为每个 `enabled=true` 的机器人创建独立运行时；之后按调和周期重新读取配置。禁用、删除、修改 revision 或切换数据库都会停止旧运行时并按最新配置收敛。

生产机器人连接顺序如下：

1. 解密该机器人的 AppSecret，向 `https://bots.qq.com/app/getAppAccessToken` 请求 Access Token。
2. 携带 Token 请求生产 OpenAPI `https://api.sgroup.qq.com/gateway/bot`；沙箱机器人使用 `https://sandbox.api.sgroup.qq.com/gateway/bot`。
3. 校验 QQ 返回的分片建议和 session start limit，然后连接响应中的动态 `wss://` 地址。
4. 收到 Gateway `HELLO` 后发送 Identify；存在有效 session snapshot 时可发送 Resume。
5. 只有收到 `READY` 后运行状态才进入 `ONLINE`，Dashboard 才把该机器人计入“已连接”。

Web 首次设置向导及“机器人”页新建表单默认 Intents 为 `33554432`（`2^25`，`GROUP_AND_C2C_EVENT`，群聊与单聊消息事件）。数据库迁移也会把旧的 `intents=0` 配置更新为该值。Intents 只是客户端请求掩码，不能绕过 QQ 开放平台的事件订阅、私域权限或账号审批；通过 API 创建机器人时仍应显式提交正确的 `intents`。

默认需要放行以下出站访问：

| 用途 | 默认目标 | 协议 |
| --- | --- | --- |
| Access Token | `bots.qq.com` | HTTPS/443 |
| 生产 OpenAPI 与 Gateway discovery | `api.sgroup.qq.com` | HTTPS/443 |
| 沙箱 OpenAPI 与 Gateway discovery | `sandbox.api.sgroup.qq.com` | HTTPS/443 |
| Gateway 长连接 | `/gateway/bot` 响应中的动态主机 | WSS，通常为 443 |

Gateway 是容器主动建立的出站连接，QQ 不需要直接入站访问容器；对外开放的 `8080` 仅用于 Web 管理。防火墙不能只按固定 Gateway IP 放行，因为 WSS 主机由 QQ discovery 动态返回。还应保证 Debian 时间同步正常、系统 CA 可用，并避免代理截断空闲 WebSocket。

### Gateway 事件进入 Inbox

收到普通 Gateway Dispatch 后，应用按以下顺序处理：

```text
WebSocket Dispatch
  -> 解析事件类型、平台事件 ID 和原始 JSON
  -> event_inbox 去重写入
  -> 写入成功后推进 Gateway Resume 序号
  -> GET /api/events/inbox -> 管理后台“事件与任务”
```

管理员接口示例（先完成登录并保留会话 Cookie）：

```bash
curl -b cookies.txt 'http://127.0.0.1:8080/api/events/inbox?limit=50'
curl -b cookies.txt 'http://127.0.0.1:8080/api/events/inbox/<event-id>'
```

Inbox 列表接口支持 `cursor`、`query`、`botId`、`environment`、`status` 和 `eventType` 筛选；列表不返回原始 Payload，详情接口以纯文本返回并限制为 1 MiB。事件写入失败时会保留旧 Resume 序号并触发可恢复重连，避免 Gateway 已确认但 Inbox 丢失。

Outbox/DLQ 管理接口为：

- `GET /api/events/outbox` 和 `GET /api/events/outbox/{id}`：任务列表、筛选、游标分页和详情。
- `GET /api/events/outbox/stats`：各状态数量统计。
- `GET /api/events/dlq` 和 `GET /api/events/dlq/{id}`：固定只读 `DEAD_LETTER` 的死信列表和详情。
- `GET /api/events/dlq/stats`：死信视图使用同一份安全统计结构。

列表不会读取或返回完整 Payload，详情以纯文本返回并限制为 1 MiB；队列 lease owner 和 fencing token 永不暴露。Outbox 详情会显示产生任务的插件绑定 ID，以及成功响应中的 QQ 真实消息 ID、序号和平台时间。Outbox worker 已连接生产 QQ OpenAPI：文本、Markdown、Keyboard、Ark、Embed 和媒体任务按机器人隔离凭据发送，429/5xx 有界重试，永久错误进入 Outbox DLQ，超时或响应无法解析进入 `RESULT_UNKNOWN`。插件宿主产生的任务同样先持久化再发送，并可由创建任务的绑定按 `jobId` 跨重启查询回执。

机器人页提供消息入队入口。`POST /api/bots/{botId}/media` 上传本地媒体，`POST /api/bots/{botId}/messages` 入队文本、富消息、远程媒体或已上传媒体。机器人编辑页的媒体上限默认 `16 MiB`，范围 `1-256 MiB`；浏览器、服务端流式写入、SDK 和发送 worker 均执行上限校验。远程 URL 会在服务端受控下载，不会直接交给 QQ 绕过大小限制。

插件投递管理接口：

- `GET /api/events/plugin-deliveries`、`/stats`、`/{id}`：插件投递列表、统计和详情。
- `GET /api/events/plugin-dlq`、`/{id}`：仅返回 `DEAD_LETTER` 插件投递。
- `GET/POST/PUT/DELETE /api/plugin-bindings`：按机器人创建、查询、启停和删除插件绑定；新建请求中的 `configJson` 只用于初始化文件，绑定响应不返回配置正文。
- `POST /api/plugin-bindings/{bindingId}/reset`：恢复已启用且配置有效的隔离绑定。

绑定文件管理接口全部以绑定目录为根，`path`/`directory` 使用正斜杠相对路径：

- `GET /api/plugin-bindings/{bindingId}/files?path=`：列出根目录或指定子目录，目录优先、名称不区分大小写排序。
- `GET /api/plugin-bindings/{bindingId}/files/content?path=...`：以 UTF-8 读取不超过 `2 MiB` 的文本，并返回 SHA-256 和修改时间。
- `PUT /api/plugin-bindings/{bindingId}/files/content`：保存文本；请求包含 `path`、`content` 和可选 `expectedSha256`，哈希不匹配返回冲突。
- `POST /api/plugin-bindings/{bindingId}/files/entries`：按请求中的 `path` 和 `directory` 创建文件或目录；创建根目录 `config.json` 时自动写入插件默认配置。
- `POST /api/plugin-bindings/{bindingId}/files/upload?directory=...&overwrite=false`：上传文件到指定目录；默认拒绝替换同名文件并返回 `409`，管理员确认后才可改为 `overwrite=true`，还可传 `expectedSha256` 做覆盖前校验。普通文件受当前全局 multipart `256 MiB` 上限约束，`config.json` 另限 `64 KiB`。
- `GET /api/plugin-bindings/{bindingId}/files/download?path=...`：以附件下载任意普通文件。
- `DELETE /api/plugin-bindings/{bindingId}/files?path=...`：永久删除指定文件或整个子目录；不能用空路径删除绑定根目录。

插件后台通过 `GET /api/plugins` 扫描 `/plugins` 目录中的 JAR manifest 和 SHA-256；PF4J 宿主只加载同时声明 `Plugin-Config-Schema`、`Plugin-Default-Config`、API 版本和有效能力的可信 JAR，默认配置必须是符合 Schema 的 JSON 对象。后台制品清单只接受不超过 `64 KiB` 的默认配置资源，绑定 `config.json` 也限制为 `64 KiB`。插件页可以选择并上传 `.jar`，通过 `POST /api/plugins/upload` 完成校验和同进程无重启热升级，失败会尝试恢复旧插件；请求必须是已认证管理员并带 CSRF 和 `X-Plugin-Upload-Confirm: trusted-jar`。

“机器人绑定”列表显示机器人信息、Gateway 状态、绑定插件数和更新时间；机器人详情按插件分区，只显示插件名、右侧删除操作和目录文件管理器，并可继续新增插件。文件管理器支持目录浏览、新建、上传、下载和递归删除；点击 `.json` 文件会打开编辑器，并以读取时的 SHA-256 防止静默覆盖并发修改。同名上传默认拒绝覆盖，Web 会要求管理员确认后再显式重试。新建绑定对话框从 `GET /api/plugins` 返回的 `defaultConfigJson` 预填默认配置，管理员确认后才创建 `/data/plugin-data/<botId>/<pluginId>/config.json`。一个插件可绑定多个机器人，每个机器人/插件组合的目录完全分开，JSON 配置不写入主数据库；插件可在自己的目录保存 SQLite、图片、音频、视频及任意其他文件。删除绑定会永久删除该绑定的整个目录，无法从数据库记录恢复。

停用绑定会暂停未完成投递，重新启用后继续；超时执行先合作取消，未在宽限期停止则进入 `QUARANTINED`，页面显示原因并提供恢复操作。声明 capability 的插件可使用按绑定 UUID 隔离的 `PluginStorage`、调度器、富消息、本地/远程媒体和多 handler 事件订阅；`PluginHttpClient` 始终提供，宿主不限制目标 URL、网络地址、请求头、重定向、正文、响应或最长超时。

PF4J 插件与宿主运行在同一 JVM，是运维人员显式信任的进程内代码，不是安全沙箱。绑定目录隔离和 Web 文件接口的路径校验用于防止管理员误操作串目录，不能阻止恶意插件直接访问容器进程身份有权访问的其他文件、网络或资源；不得安装来源不可信的 JAR。

镜像携带 `example` 示例插件，`stage-compose-extensions.sh` 会把它提取到 `./plugins/qqbot-plugin-example.jar`。在 Web 插件页把它绑定到机器人时，可先修改预载的 `config.json`；默认发送 `/example` 可验证回复 `example reply` 的完整闭环。每个机器人绑定保存独立配置。已有插件不会因镜像升级自动替换，只有再次显式执行制品提取或手动替换 JAR 才会更新；从旧版升级时还应删除遗留的 `qqbot-plugin-echo*.jar`，避免同时加载两个示例插件。

后台审计过滤器会记录所有管理变更请求的 HTTP 方法、路径、结果状态、操作者、来源地址和 trace ID，不保存请求体；通过 `GET /api/audit-logs` 分页查询。账户安全区支持旧密码校验后改密；机器人删除会二次确认并清理该机器人 Inbox、Outbox、插件绑定和投递记录。运行状态可通过 `GET /api/bots/runtime/stream` 订阅 SSE，客户端断线会回到轮询。

多实例部署时每个机器人 Shard 使用 `bot_leases` SQL 租约。实例通过唯一的 `QQBOT_INSTANCE_ID` 标识自己，只有持有未过期租约的实例才建立 Gateway，Inbox、插件投递和 Outbox 也只领取属于该实例机器人的任务；处理前会再次核对租约归属。启用插件的机器人在获取和续租时还会对比活动数据库中的插件 SHA-256，不匹配实例会释放租约。管理员 Session 和登录失败限流同样存入活动数据库。SQLite 通过数据库旁的 `.instance.lock` 文件拒绝第二个进程，并在数据库热切换时转移锁；真正多实例必须使用共享 MySQL/PostgreSQL。

所有实例必须使用不同且稳定于单次进程生命周期的 `QQBOT_INSTANCE_ID`，共享同一个活动数据库和相同插件制品。网页插件上传只更新收到请求的实例，不是集群制品分发方案。多实例还必须把 `QQBOT_PLUGINS_DATA_DIR` 挂载为所有实例可读写的同一共享文件系统，否则绑定配置、插件 SQLite 和媒体文件会因请求或机器人租约落到不同实例而分裂。若多实例可能处理网页上传的本地媒体，`QQBOT_MEDIA_STAGING_DIRECTORY` 也必须共享。默认本地 Docker Volume 只适合作为单主机部署基线；共享文件系统上的并发、文件锁和 SQLite 兼容性由插件及部署者验证。

Web 文件管理、删除插件绑定和删除机器人只会同步停止处理该请求实例内的插件执行，不能确认其他实例上的回调已经结束。HA 部署执行这些维护操作前，必须先把目标机器人租约及管理请求收敛到一个实例，或暂时停掉其他应用副本，并确认远端插件已释放 SQLite 和文件句柄；否则不得对共享插件目录执行写入或删除。默认单实例 Compose 不需要额外步骤。

当前配置项如下。Compose 已直接映射常用模块、Gateway、OneBot 和插件配置，并固定把模块目录设为 `/modules`、session 目录设为 `/data/config/gateway-sessions`、OneBot 缓存设为 `/data/onebot-cache`、插件制品目录设为 `/plugins`、插件绑定数据目录设为 `/data/plugin-data`、媒体目录设为 `/data/media-staging`；要覆盖表中其他项，需要在 `compose.yaml` 的 `environment` 下显式传入。

| 环境变量 | 应用默认值 | 作用 |
| --- | --- | --- |
| `QQBOT_GATEWAY_ENABLED` | `true` | 是否启动 Supervisor 和真实 Gateway 连接 |
| `QQBOT_GATEWAY_SESSION_DIRECTORY` | `gateway-sessions`；Compose 为 `/data/config/gateway-sessions` | Resume snapshot 目录 |
| `QQBOT_GATEWAY_RECONCILE_INTERVAL` | `15s` | 从活动数据库重新调和机器人期望状态的周期 |
| `QQBOT_GATEWAY_LEASE_DURATION` | `45s` | 单实例 bot/shard SQL 租约有效期，应明显大于调和周期 |
| `QQBOT_INSTANCE_ID` | 自动随机 UUID | 多实例租约 owner 标识；同一实例重启可使用新值 |
| `QQBOT_GATEWAY_SHUTDOWN_TIMEOUT` | `10s` | 停止全部机器人运行时的最长等待时间 |
| `QQBOT_GATEWAY_CONNECT_TIMEOUT` | `10s` | 建立 WSS 连接的超时 |
| `QQBOT_GATEWAY_MAX_TEXT_CHARACTERS` | `2097152` | 单个 Gateway 文本帧允许的最大字符数 |
| `QQBOT_ONEBOT11_CACHE_DIRECTORY` | `onebot-cache`；Compose 为 `/data/onebot-cache` | `get_image/get_record` 的受控下载缓存；必须位于可写目录 |
| `QQBOT_PLUGINS_DIR` | `/plugins` | 可信插件扫描、上传和版本制品目录；启用网页上传时必须可写 |
| `QQBOT_PLUGINS_DATA_DIR` | `/data/plugin-data` | 每个机器人/插件绑定的 `config.json` 和插件自有文件根目录；必须持久化，多实例时必须共享 |
| `QQBOT_MODULES_DIR` | `/modules` | 启动时严格扫描的框架模块 JAR 目录；容器内只读 |
| `LOADER_PATH` | `/modules` | `PropertiesLauncher` 启动类路径；必须与模块目录一致 |
| `QQBOT_PLUGINS_LEASE_DURATION` | `30s` | 插件投递领取租约，必须覆盖一次正常执行 |
| `QQBOT_PLUGINS_EXECUTION_TIMEOUT` | `20s` | 单次事件 handler 最长执行时间 |
| `QQBOT_PLUGINS_CANCELLATION_GRACE` | `5s` | 超时发出取消后等待插件合作停止的宽限期 |
| `QQBOT_PLUGINS_BINDING_QUEUE_CAPACITY` | `256` | 每个插件绑定的独立执行队列容量 |
| `QQBOT_PLUGINS_SHUTDOWN_TIMEOUT` | `20s` | 热升级或停用时等待绑定在途任务的最长时间 |
| `QQBOT_OUTBOX_LEASE_DURATION` | `45s` | Outbox 任务领取租约 |
| `QQBOT_OUTBOX_REQUEST_TIMEOUT` | `20s` | worker 等待一次 QQ 请求的时间 |
| `QQBOT_MEDIA_STAGING_DIRECTORY` | `media-staging`；Compose 为 `/data/media-staging` | 网页/插件本地媒体暂存目录；多实例时必须共享 |

QQ HTTP 客户端还支持 `QQBOT_QQ_REQUEST_TIMEOUT`（默认 `10s`）、`QQBOT_QQ_TOKEN_REFRESH_SKEW`（默认 `60s`）、`QQBOT_QQ_TOKEN_ENDPOINT`、`QQBOT_QQ_OPEN_API_BASE_URI` 和 `QQBOT_QQ_SANDBOX_OPEN_API_BASE_URI`。后三项默认就是上表官方地址，除受控测试或明确的企业代理场景外不建议覆盖。

## 持久化目录

Compose 使用以下持久化位置：

| 内容 | 容器路径 | 宿主形式 |
| --- | --- | --- |
| SQLite 与运行数据 | `/data` | `qqbot-data` 命名卷 |
| 活动/候选数据库配置 | `/data/config` | `./config` 绑定目录 |
| 首次设置进度 | `/data/config/onboarding.json` | `./config/onboarding.json`，应用自动维护 |
| Gateway Resume 状态 | `/data/config/gateway-sessions` | `./config/gateway-sessions`，应用自动维护 |
| 媒体暂存 | `/data/media-staging` | `qqbot-data` 命名卷；终态任务自动删除对应文件 |
| OneBot 媒体缓存 | `/data/onebot-cache` | `qqbot-data` 命名卷；由 `clean_cache` 按机器人清理 |
| 插件绑定配置与数据 | `/data/plugin-data/<botId>/<pluginId>/` | `qqbot-data` 命名卷；每个绑定包含 `config.json` 和插件自有文件 |
| 框架模块 | `/modules` | 宿主机 `./modules` 只读绑定目录 |
| 机器人插件 | `/plugins` | 宿主机 `./plugins` 可写绑定目录 |
| 主密钥 | `/data/config/app-secret.key` | `./config/app-secret.key`，首次启动自动生成 |
| 临时文件 | `/tmp/qqbot` | 内存 tmpfs |

容器以 UID/GID `10001` 非 root 身份运行，根文件系统只读。`config` 权限不正确时，服务会无法生成主密钥或提交数据库配置；`/data/plugin-data` 不可写时，插件绑定无法初始化或通过 Web 管理文件。

插件绑定目录不存入数据库，也没有回收站。删除绑定会先停止并失效化绑定实例，再递归永久删除对应 `<botId>/<pluginId>/` 目录；删除单个文件或目录同样立即生效。删除或破坏根目录的 `config.json` 会使绑定进入隔离状态，重新创建有效配置后才可恢复。插件自行创建的 SQLite 连接、文件格式、迁移、备份和关闭流程由插件负责。

每个机器人分片的 Resume snapshot 文件名为 `<botId>-shard-<index>.json`，内容包含 session id、最后事件序号和配置指纹，不包含 AppSecret 或 Access Token。文件通过临时文件原子替换，在 POSIX 文件系统上临时文件使用 `0600`。机器人 revision、AppID、环境、Intents 或分片配置变化导致指纹不匹配时，旧 snapshot 会被删除并重新 Identify；有效 snapshot 可用于重连或重启后的 Resume。该目录应随 `config` 一起备份和恢复，但不能替代数据库与主密钥备份。

## 后续从 Web 后台切换数据库

1. 登录管理后台并进入“系统”。
2. 选择 SQLite、MySQL 或 PostgreSQL，填写路径或连接参数。
3. 先执行连接测试。应用会检查连接、schema、读取，以及在事务回滚范围内的写入。
4. 确认后执行切换。切换期间新数据库会再次完整验证，失败时保持原 DataSource 和活动配置不变。
5. 若目标库完全为空，应用会执行 Flyway 初始化并复制当前唯一管理员，因此切换后当前会话和后续登录可继续使用。

数据库之间不会复制机器人、Inbox 或 Outbox 业务数据。非空目标库如果没有当前管理员用户名，切换会被拒绝，以免切换后失去后台访问能力。`/data/plugin-data` 独立于活动数据库，数据库切换不会复制、删除或重命名其中的配置和插件文件；新数据库中 `botId`/`pluginId` 组合相同的绑定会继续使用对应目录，不存在于新库的目录会作为未引用数据保留，必须由运维人员在完整备份后人工处理。

数据库切换提交后，Supervisor 会先同步隔离旧数据库对应的全部运行时和会话代际，使旧连接的迟到回调不能再修改当前状态，并对旧 WSS 会话发起停止；随后只从新活动数据库重新读取机器人并创建运行时。新库没有的机器人不会继续运行，新库内 `enabled=true` 的机器人会重新建立运行时。配置指纹完全相同的持久化 snapshot 仍可能用于协议 Resume，但这不会让旧数据库的运行时对象继续存活。

## 候选配置文件切换

应用使用两个文件：

- `config/database.json`：已生效配置，由应用原子写入；服务端数据库密码为 AES-256-GCM 密文。
- `config/database-candidate.json`：人工编辑的候选配置，只在主动“从配置文件重新加载”时读取。

这种分离保证候选配置连接失败、无写权限或 schema 不兼容时，不会破坏下次启动所需的活动配置。

SQLite 示例：

```bash
sudo cp config/database.sqlite.example.json config/database-candidate.json
sudo chown 10001:10001 config/database-candidate.json
sudo chmod 0600 config/database-candidate.json
```

MySQL/PostgreSQL 示例使用相对密码文件：

```bash
sudo cp config/database.mysql.example.json config/database-candidate.json
printf '%s' 'replace-with-database-password' | sudo tee config/database-password >/dev/null
sudo chown 10001:10001 config/database-candidate.json config/database-password
sudo chmod 0600 config/database-candidate.json
sudo chmod 0400 config/database-password
```

编辑候选文件后，在 Web 系统页点击“从配置文件重新加载”。也可以在已认证且带 CSRF Token 的客户端调用 `POST /api/system/database/reload`。成功后应用将候选内容规范化、加密密码并提交到 `database.json`；候选文件可保留用于后续修改。

枚举值必须使用大写：数据库类型为 `SQLITE`、`MYSQL`、`POSTGRESQL`；SSL 模式为 `DISABLED`、`PREFERRED`、`REQUIRED`、`VERIFY_CA`、`VERIFY_IDENTITY`。

## 外部数据库网络

数据库不在 Compose 内。连接地址可以是局域网 DNS/IP；如果数据库运行在同一台 Debian 宿主机，可使用 Compose 已映射的 `host.docker.internal`，并确保数据库监听宿主接口且防火墙允许 Docker 网段访问。容器中的 `localhost` 始终指应用容器本身，不能代表宿主机。

生产环境建议：

- 为应用创建独立数据库和独立账号，不使用 root/superuser。
- 限制数据库防火墙来源，只允许 Docker 主机或指定容器网段。
- 跨主机连接使用 `REQUIRED` 或更严格的证书校验模式。
- 在切换前先完成目标库备份和连接测试。

## 健康检查与排障

```bash
curl -fsS http://127.0.0.1:8080/health/live
curl -fsS http://127.0.0.1:8080/health/ready
docker compose ps
docker compose logs --tail=200 qqbot
```

`live` 只表示进程可响应；`ready` 会检查当前活动数据库，不检查每个 QQ Gateway 是否在线。因此 `/health/ready` 为 `UP` 与 Dashboard 显示 0 个已连接机器人并不矛盾。

管理员认证后可调用：

```bash
curl -b cookies.txt http://127.0.0.1:8080/api/bots/runtime
```

事件排查可同时调用：

```bash
curl -b cookies.txt 'http://127.0.0.1:8080/api/events/inbox?limit=50'
```

`admin_users.username` 是部署实例的真实管理员账号，不应假设为 `admin`；例如当前数据库中的账号是 `alanqaq`。Gateway `ONLINE`、心跳正常只说明 WebSocket 会话存活，还应确认 Inbox 列表的 `items` 是否出现新的 `receivedAt` 记录。

响应中的 `totalCount` 是 Supervisor 当前已调和的机器人数量，`enabledCount` 是期望启用数量，`connectedCount` 只统计状态为 `ONLINE` 的数量，`observedAt` 是本次快照时间。每个 `bots[]` 元素包含配置 revision、状态变更时间、最近一次 READY 时间 `connectedAt`、最后心跳、最后事件、重连次数、session id/sequence 和脱敏后的 `lastError`。机器人页显示“正在应用 rev.”表示运行时 revision 尚未追上数据库配置。

状态含义：`DISABLED` 未要求运行；`STARTING` 正在创建运行时；`DISCOVERING` 正在获取 Token/查询 `/gateway/bot`；`CONNECTING` 正在连接 WSS 或等待 HELLO；`AUTHENTICATING` 正在 Identify/Resume；`ONLINE` 已收到 READY；`RECONNECTING` 正在退避重连；`STOPPING`/`STOPPED` 正在或已经释放会话；`FAILED` 是需要修正配置、权限或其他终止性问题。Dashboard 的“平台服务”来自健康接口，“运行机器人”来自该 runtime API，两者应分别判断。

常见问题：

- `permission denied`：修正 `config` 及其中配置文件的 UID/GID 和模式。
- 首次设置无法恢复：检查 `config/onboarding.json` 是否可读且为有效 JSON，并从同一批备份恢复；不要手工跳过阶段。
- 外部数据库连接失败：确认没有填写 `localhost`、端口可达、账号授权和 TLS 模式匹配。
- `QQ_AUTHENTICATION_REJECTED` 或 `GATEWAY_AUTHENTICATION_FAILURE`：检查 AppID/AppSecret 是否匹配、生产/沙箱环境是否选对、机器人是否已在 QQ 开放平台启用；修改凭据后保存新 revision，禁止在日志或工单中粘贴 AppSecret。
- `GATEWAY_INTENTS_REJECTED`（QQ close code 4013/4014）：Intents 掩码无效，或请求了账号未获批的事件权限。先恢复默认 `33554432` 验证基础连接，再按 QQ 平台授权逐项增加权限。
- `GATEWAY_SESSION_LIMITED`：`/gateway/bot` 返回的 session start limit 已用尽，运行时会等到 QQ 返回的 reset 时间再发现；不要反复重启容器，也不要用同一机器人凭据启动重复实例。
- `QQ_RATE_LIMITED` 或 `GATEWAY_RATE_LIMITED`：QQ discovery 或 WSS 触发限流，运行时会退避重试；检查重复部署、过快重启和配额使用情况。
- `GATEWAY_SHARD_CONFIGURATION_INVALID` 或 `GATEWAY_SHARD_REJECTED`：配置的分片数量超过 discovery 建议，或分片参数被 QQ 拒绝；单实例通常保持单分片，扩容前先以 `/gateway/bot` 返回值为准。
- `QQ_BOT_OFFLINE` / `QQ_BOT_BANNED`：QQ 认为该环境中的机器人不可用或禁止连接，需要在开放平台确认上线、封禁及环境状态，应用不会绕过该限制。
- `QQ_REQUEST_TIMEOUT`、`QQ_TRANSPORT_UNAVAILABLE` 或持续 `RECONNECTING`：从容器内检查 DNS、HTTPS/WSS 443、系统时间、CA、代理 WebSocket 支持和动态 Gateway 域名放行；不要仅检查 Web 后台的入站端口。
- 镜像构建摘要失败：根目录 Dragonwell 压缩包不是已验证版本或文件已损坏。
- 切换提示目标管理员缺失：目标库已有业务写入但没有当前管理员；应用不会自动覆盖非空库。
- 重启后无法解密：恢复最初的 `config/app-secret.key`，不要生成新密钥覆盖。

## 备份与升级

本次插件配置改造是不兼容升级：Flyway `V014` 会直接删除绑定表中的 `config_json`，不会把旧数据库配置迁移到文件。升级后，已有绑定缺少 `/data/plugin-data/<botId>/<pluginId>/config.json` 时会从该插件的 `Plugin-Default-Config` 创建默认配置；没有声明有效默认配置的旧插件 JAR 不会加载。若仍需旧配置值，必须在升级前自行导出并在升级后通过 Web 文件管理器写入 `config.json`。

SQLite 部署在备份前先停止写入：

```bash
mkdir -p backup
docker compose stop qqbot
docker compose cp qqbot:/data ./backup/data
sudo cp -a config ./backup/config
sudo cp -a modules plugins ./backup/
docker compose start qqbot
```

MySQL/PostgreSQL 使用对应数据库的原生备份工具；同时备份完整 `/data`、`config/database.json`、`config/onboarding.json`、主密钥、`modules/` 和 `plugins/`。停止应用后备份 `/data` 会同时保留 `/data/plugin-data` 中不入数据库的绑定配置、插件 SQLite/媒体文件，以及仍在 Outbox 中等待发送的本地媒体。只备份业务数据库无法恢复插件配置和插件自有文件。

升级流程：

```bash
docker compose stop qqbot
# 完成数据、配置和主密钥备份后更新项目文件
docker compose build --pull --no-cache
# 采用新版本默认模块时显式执行；自定义模块仍会保留
sh ./scripts/stage-compose-extensions.sh
sudo chown -R 10001:10001 config plugins
docker compose up -d
docker compose ps
docker compose logs --tail=200 qqbot
```

核心、Gateway/Inbox、Outbox/DLQ 或后台壳更新时，已有容器只执行 `restart` 不会加载新核心代码，必须重新构建镜像并执行 `up -d`。单独更新框架模块时，把包含后端类和 Web Component 的新 JAR 原子替换到 `./modules`，保留一套完整可回滚副本，然后执行 `docker compose restart qqbot`。启动校验失败会阻止应用进入可用状态，应从日志确认具体模块并恢复旧 JAR。`qqbot-data` 卷中的 `/data/plugin-data`、`./config`、`./modules`、`./plugins` 和外部 MySQL/PostgreSQL 不会因容器重建而删除；这不等于备份，删除绑定仍会永久删除其目录。

模块开发、依赖声明、模块间服务和 Web Component 接入见 [MODULE_DEVELOPMENT.md](./MODULE_DEVELOPMENT.md)；机器人插件开发和上传见 [PLUGIN_DEVELOPMENT.md](./PLUGIN_DEVELOPMENT.md)。

Flyway 只执行向前迁移。升级前的数据库备份是回退依据，不要手工修改 `flyway_schema_history`。
