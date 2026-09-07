# Changelog

本文件记录面向使用者的发布变更。插件 API 级别与 Maven 制品版本分别维护；请同时阅读对应 SDK 指南。

## 1.0.8 - 2026-09-07

### Added

- C2C/群聊媒体发送使用 QQ 官方分片预上传、预签名分块上传、分块完成和文件合并流程，支持服务端重试预算和最长 200 MiB 的分片媒体。

### Fixed

- 长媒体上传会续租 Outbox 任务，并在发送成功或死信后清理暂存文件；结果未知时保留文件供人工核查。
- Compose 镜像、模块制品、插件模板和 SDK 分发版本统一为 `1.0.8`；仓库包含可直接部署所需制品与当前 Graphify 输出。

## 1.0.7 - 2026-08-13

### Fixed

- Docker 运行镜像现在包含 Fontconfig、Noto CJK 和 FreeType，固定启用 headless AWT，并在构建时验证中文字体选择和字体度量路径，避免图像渲染插件因容器字体环境缺失而失败。
- 离线缓存运行时镜像在复制制品前验证同一套 AWT/字体前置条件，过期的缓存基础镜像会明确失败并要求重新构建。

### Distribution

- 源码仓库继续提交七个默认框架模块 JAR 和示例插件；下载后可直接执行 `docker compose up -d --build`，首次启动由 `qqbot-prepare` 初始化空的模块和插件目录。

## 1.0.6 - 2026-08-03

### Fixed

- 插件类加载器对宿主共享的插件 API、SPI 和领域契约采用父加载器优先，避免插件携带重复契约类时产生类型不兼容。

## 1.0.3 - 2026-07-31

### Changed

- 删除插件默认配置和绑定根配置的 64 KiB 专属限制；配置内容继续按插件声明的 JSON/YAML 格式校验并原样交给插件，普通非配置文本编辑/预览仍保留 `2 MiB` 限制。

## 1.0.2 - 2026-07-30

### Added

- 插件 API 升至 `3.2.0`，新增原始配置正文 `ConfigSnapshot.content`、`PluginContext.configurationContent`、配置文件名和 `PluginRuntimeContext.configurationFile`；插件可自行选择 JSON 或 YAML 加载方式。
- `Plugin-Default-Config` 支持 `.json`、`.yml` 和 `.yaml`，扩展名分别选择绑定文件 `config.json`、`config.yml` 和 `config.yaml`；后台新增绑定与文件编辑器同步支持三种扩展名。

### Compatibility

- 宿主只为对象类型和 JSON Schema 校验临时解析配置，不会把 YAML 转换成 JSON，也不会规范化配置正文。既有 JSON 插件可继续使用 `ConfigSnapshot.json`、`PluginContext.configurationJson`、`defaultConfigJson` 和创建请求 `configJson`，无需重新编译。

## 1.0.1 - 2026-07-28

### Added

- 插件 API `3.1.0` 新增 `MessageSendOptions` 和显式 `MessageReference`，文本、媒体、暂存媒体和富消息都可经持久化 Outbox 发送 QQ `message_reference`；原有 `msg_id`/`event_id` 被动回复保持不变。
- 管理后台的测试消息表单和发送接口支持显式引用消息 ID，并在 Outbox 任务 Payload 中展示引用选项。

### Changed

- 插件 API 兼容检查改为接受同一主版本且不高于宿主版本的接口级别，因此现有 `3.0.0` 插件可继续由 `3.1.0` 宿主加载。

### Fixed

- 普通群消息现在从 QQ `author.member_role` 解码并向原生插件暴露稳定的 `GroupMemberRole`；OneBot 第三方 WebSocket 事件不再把群主和管理员固定降级为普通成员。
- 明确 OneBot 11 只用于外部第三方 WebSocket 接入，与 PF4J 机器人插件开发无关。
- `stageDefaultModules` 现在会清理七个默认模块的旧版本 JAR，避免框架升级后因本地 `/modules` 残留版本而拒绝启动。

## 1.0.0 - 2026-07-26

### Changed

- 将内置 `echo` 插件重做为 `example`，制品改名为 `qqbot-plugin-example.jar`；新增可在机器人绑定弹窗中预设的 `config.json`，按 `triggerKeyword` 精确匹配消息并回复 `replyContent`。
- 将框架、默认模块、Compose 默认镜像、SDK 模板和 OneBot `get_version_info` 的发行版本统一为 `1.0.0`。

## 0.4.1 - 2026-07-24

### Changed

- 将框架、默认模块、接入库、持久化、管理 API、测试和插件模板的源码统一迁移为 Kotlin/JVM。
- 插件 API 提升为 `3.0.0`，删除旧 Java ABI：record 风格访问器改为属性，`Optional<T>` 改为 Kotlin 可空类型，`RestrictedHttpClient` 更名为无限制的 `PluginHttpClient`，消息、媒体和生命周期实现不再继承旧默认回退。
- 模块 SPI 的描述符、模块 ID 和服务注册状态改为 Kotlin 属性，并删除旧运行时 Dispatch 序号适配回调。
- 原生插件消息 Outbox 在成功发送时原子保存 QQ 消息 ID、消息序号和平台时间，并通过按绑定隔离的 `MessageSender.findDelivery(jobId)` 提供持久化查询。
- 稳定 `InboundMessage` 增加 `referencedMessageId`，直接暴露 QQ 引用消息 ID，便于插件建立可跨重启恢复的引用链。
- 将应用、默认框架模块、Compose 默认镜像、SDK 模板和 OneBot `get_version_info` 的发行版本统一为 `0.4.1`。
- 更正项目文档中的 Kotlin 编译器/运行时版本、Node.js 支持范围、模块数量和前端模块页面包范围。
- 明确当前未集成 Testcontainers、Angular E2E、OpenAPI/TypeScript Client 自动生成和 CI 二进制兼容检查。
- 将本变更记录纳入插件 SDK、模块 SDK 和默认模块分发包。

### Documentation

- 以项目专用说明替换 Angular CLI 生成的前端 README，提供实际构建、测试和开发命令。

## 0.4.0 - 2026-07-23

### Changed

- 插件绑定配置从数据库 `bot_plugins.config_json` 迁移到每个绑定目录的 `config.json`。
- 新增插件默认配置声明与校验，以及绑定文件管理。

### Upgrade note

- Flyway V014 删除旧 `config_json` 字段，旧配置不会自动迁移到文件；升级前必须导出并在升级后恢复需要保留的值。
