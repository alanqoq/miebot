# MieBot 插件模板

这是一个可复制的 Kotlin 1.9.25、JVM 21、插件 API 3.2 项目。模板只把 `qqbot-plugin-api` 和
`qqbot-plugin-spi` 作为 `compileOnly` 依赖，最终 JAR 不会携带 API、SPI、PF4J、
Spring 或数据库驱动。

## 在本仓库构建

先从仓库根目录发布 SDK 到本地分发仓库：

```powershell
& 'E:\JAVA\dragonwell-21.0.31.0.31+10-GA\bin\java.exe' `
  -classpath '.\.tools\gradle-8.14.3\lib\gradle-launcher-8.14.3.jar' `
  org.gradle.launcher.GradleMain pluginSdkRepository --no-daemon
```

然后构建并运行模板测试（可把整个 `plugin-template` 目录复制到其他项目）：

```powershell
& 'E:\JAVA\dragonwell-21.0.31.0.31+10-GA\bin\java.exe' `
  -classpath '.\.tools\gradle-8.14.3\lib\gradle-launcher-8.14.3.jar' `
  org.gradle.launcher.GradleMain -p .\plugin-template clean test jar `
  --no-daemon
```

复制到其他目录后，通过 `-PqqbotSdkRepository=<SDK 仓库路径>` 或环境变量
`QQBOT_SDK_REPOSITORY` 指定 SDK 仓库。SDK 版本默认是仓库当前的
`1.0.3`。

## 需要改动的文件

- `src/main/kotlin/com/example/qqbot/TemplatePluginFactory.kt`：插件 ID、生命周期和事件处理逻辑。
- `src/test/kotlin/com/example/qqbot/TemplatePluginFactoryTest.kt`：模板测试。
- `META-INF/services/...BotPluginFactory`：保持只注册一个工厂类。
- `qqbot-plugin-schema.json`：绑定配置的 JSON Schema。
- `qqbot-plugin-default.json`：新建绑定时加载的默认配置，必须符合 Schema；也可改为 `.yml`/`.yaml` 并同步修改 Manifest。
- `build.gradle.kts`：Manifest 的 ID、版本和 capabilities。

模板示例使用 `EventService` 注册 `commands` 和 `audit` 两个 handler；不需要
命名 handler 时可保留一个订阅。宿主会为每个匹配的 handler 创建独立投递记录。
`BotPluginFactory.create(PluginRuntimeContext)` 为每个机器人绑定创建实例，实例在
`start()` 中通过 `EventService` 注册命名 handler，并在 `stop()` 中关闭订阅。
每个绑定的私有目录通过 `context.base.dataDirectory` 获取。默认配置资源扩展名决定目录中使用
`config.json`、`config.yml` 或 `config.yaml`；它和插件
创建的 SQLite、图片、音频、视频等文件都存放在该目录中；不同机器人和插件的目录
彼此独立。运行配置原文从 `context.configuration.content` 获取，实际文件可从
`context.configurationFile` 获取，解析方式由插件决定。旧 JSON 插件仍可使用
`context.configuration.json`。

## 发送回执与引用消息

`MessageSender.enqueue(...)` 返回的是 Outbox 入队凭据，不是 QQ 发送成功凭据。需要保存
机器人真实消息 ID 的插件，应把 `MessageEnqueueReceipt.jobId` 先写入绑定目录中的
SQLite，再通过 `context.base.messageSender.findDelivery(jobId)` 查询持久化结果。

只有 `MessageDeliveryState.SUCCEEDED` 表示已确认成功，此时读取
`MessageDeliveryReceipt.platformMessageId` 并按 `jobId` 更新 SQLite。`PENDING`、
`IN_PROGRESS`、`RETRY_WAIT` 需要稍后再查；`RESULT_UNKNOWN` 和 `DEAD_LETTER` 是终态，
前者表示消息可能已发送但没有可确认的 QQ 消息 ID。群友以后引用机器人消息时，入站
`InboundMessage.referencedMessageId` 对应的是 `platformMessageId`，不是 `jobId`。

测试中，`FakeMessageSender.enqueue(...)` 会自动建立 `PENDING` 回执；可调用
`fixture.messages.succeed(jobId, "bot-message-900")` 模拟成功，或用
`setDelivery(...)` 注入其他状态。完整的三个 ID、轮询、SQLite 表结构和异常处理示例见
仓库根目录的 [PLUGIN_DEVELOPMENT.md](../PLUGIN_DEVELOPMENT.md)。
