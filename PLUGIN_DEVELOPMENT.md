# 插件开发指南

本文档描述当前代码已经实现并可使用的插件接口。需求文档中的未来规划不等同于现有 API；开发插件时应以本文件、`qqbot-plugin-api` 和 `qqbot-plugin-spi` 的源码为准。

## 1. 运行模型

插件是由运维人员部署的可信 JAR。宿主使用 PF4J 加载 JAR，但插件作者只实现纯 Java SPI，不需要依赖 PF4J 或 Spring。

```text
QQ Gateway 事件
  -> event_inbox 持久化和去重
  -> plugin_deliveries 独立投递
  -> EventService 命名 handler 或兼容的 BotPlugin.onEvent(...)
  -> MessageSender 写入 outbox_jobs
  -> Outbox Worker 调用 QQ OpenAPI
```

- 一个 JAR 对应一个插件制品和一个 ClassLoader。
- 一个插件可以绑定多个机器人。
- 每个绑定创建独立的 `BotPlugin` 实例、配置和 `PluginStorage` 空间。
- 配置或启用状态变化时，旧实例停止，后续事件使用新实例。
- 停用绑定时未完成投递进入 `PAUSED`，重新启用后恢复。
- 插件异常或能在宽限期内停止的超时会触发有限重试，超过上限进入插件 DLQ。
- 超时后拒绝合作取消的执行会使整个绑定进入 `QUARANTINED`，需要管理员恢复。
- PF4J 类加载隔离不是安全沙箱，只能部署可信插件。

## 2. 开发环境

插件需要 Java 21。仓库内开发时，只依赖以下两个模块：

```kotlin
dependencies {
    compileOnly(project(":qqbot-plugin-api"))
    compileOnly(project(":qqbot-plugin-spi"))
    testImplementation(project(":qqbot-plugin-testkit"))
}
```

外部插件项目应依赖与宿主完全相同的 Maven 制品版本。当前平台制品版本为 `0.2.0`，Manifest 的插件 API 兼容级别为 `1.2.0`。根项目的 `pluginSdkRepository` 任务会生成可复制的本地 Maven SDK 仓库，`pluginSdkDistribution` 会把仓库、模板和本指南打成 ZIP；不需要把宿主模块或 PF4J 放进插件项目。

必须使用 `compileOnly` 或 Maven 的 `provided` scope。不要把 API/SPI、PF4J、Spring、数据库驱动或宿主模块打入插件 JAR，否则可能出现类型不相等、类加载冲突或越过宿主安全边界的问题。

仓库内可复制 `plugin-template` 作为起点；`qqbot-plugin-example` 是宿主端到端测试使用的 V2 参考实现。建议目录如下：

```text
my-plugin/
  build.gradle.kts
  src/main/java/com/example/MyPluginFactory.java
  src/main/resources/qqbot-plugin-schema.json
  src/main/resources/META-INF/services/
    com.mieai.qqbot.plugin.spi.BotPluginFactory
```

## 3. 最小插件

插件 JAR 必须通过 `ServiceLoader` 提供且只提供一个 `BotPluginFactory`。

```java
package com.example;

import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class HelloPluginFactory implements BotPluginFactory {
    @Override
    public String pluginId() {
        return "hello";
    }

    @Override
    public BotPlugin create(PluginContext context) {
        return new BotPlugin() {
            @Override
            public CompletionStage<Void> onEvent(PluginEvent event) {
                String content = event.message()
                        .flatMap(message -> message.content())
                        .map(String::strip)
                        .orElse("");
                if (!"/hello".equalsIgnoreCase(content)) {
                    return CompletableFuture.completedFuture(null);
                }
                return context.messageSender()
                        .enqueue(TextMessage.reply(event, "hello"))
                        .thenApply(receipt -> null);
            }
        };
    }
}
```

ServiceLoader 文件 `META-INF/services/com.mieai.qqbot.plugin.spi.BotPluginFactory` 的内容是工厂类全限定名：

```text
com.example.HelloPluginFactory
```

不要在文件中填写 `BotPlugin` 实现类，也不要注册多个工厂。

## 4. Manifest

宿主当前识别以下属性：

| 属性 | 必需 | 说明 |
| --- | --- | --- |
| `Plugin-Id` | 是 | 稳定插件 ID，必须与 `BotPluginFactory.pluginId()` 完全一致 |
| `Plugin-Name` | 建议 | 后台显示名称；未提供时使用插件 ID |
| `Plugin-Version` | 是 | 插件版本 |
| `Plugin-Requires` | 建议 | 宿主插件 API 兼容版本；缺省时宿主按当前 `1.2.0` 处理 |
| `Plugin-Class` | 是 | 固定为 `com.mieai.qqbot.plugin.host.Pf4jPluginBridge` |
| `Plugin-Config-Schema` | 是 | JAR 内 JSON Schema 资源路径 |
| `Plugin-Capabilities` | 建议 | 逗号分隔的能力列表 |

Gradle 配置示例：

```kotlin
tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "hello",
            "Plugin-Name" to "Hello Plugin",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "1.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Capabilities" to "event.read,message.send,storage",
        )
    }
}
```

当前接受以下能力：

| 能力 | 行为 |
| --- | --- |
| `event.read` | 接收事件；未声明时宿主会自动补充 |
| `message.send` | 使用 `MessageSender` 写入可靠 Outbox |
| `storage` | 使用绑定级 `PluginStorage` |
| `event.subscribe` | 注册多个命名事件 handler |
| `scheduler` | 使用绑定级定时任务 |
| `http` | 使用无凭据、无重定向的受限 HTTPS Client |
| `media.send` | 通过 `MediaService` 入队媒体消息 |

声明任何其他能力都会导致插件校验失败，插件不会进入已加载状态。未声明能力时宿主注入拒绝实现；发送和 HTTP 返回失败的 `CompletionStage`，存储、事件订阅和调度器会同步拒绝。`event.read` 由宿主自动补充，不能用它绕过其他能力。

## 5. 配置 Schema

每个插件都必须提供配置 Schema，即使插件不需要配置，也应提供：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

当前宿主实现的是确定性 JSON Schema 子集：

- 类型：`object`、`array`、`string`、`integer`、`number`、`boolean`、`null`
- 通用：`const`、`enum`
- 对象：`properties`、`required`、布尔值 `additionalProperties`
- 数组：`items`、`minItems`、`maxItems`
- 字符串：`minLength`、`maxLength`、`pattern`
- 数字：`minimum`、`maximum`

不要依赖 `$ref`、`oneOf`、`anyOf`、条件 Schema、格式校验或其他未列出的关键字。配置必须是 JSON 对象，后台保存绑定前会验证并返回首个具体字段错误。单个绑定配置的 HTTP 字段最多包含 65,536 个 Java 字符。

插件通过 `PluginContext.configurationJson()` 取得原始 JSON 字符串。插件公共 API 不暴露 Jackson，因此插件可以使用自身选择的 JSON 库；若将 JSON 库打入 JAR，应做依赖重定位或确认不会与父加载器冲突。

## 6. 生命周期与并发

`BotPlugin` 提供兼容旧插件的生命周期方法，并为 V2 插件增加扩展上下文重载：

```java
default void start(PluginContext context) {}
default void start(PluginRuntimeContext context) {}
default CompletionStage<Void> onEvent(PluginEvent event) {
    return CompletableFuture.completedFuture(null);
}
default void stop() {}
```

- 插件绑定实例按需创建：第一条待处理事件到来时执行 `create(context)`，随后立即调用 `start(context)`。
- 每个绑定复用同一个插件实例处理事件。
- 当前每个绑定使用独立有界执行队列；插件不应把单线程执行当作 API 保证，内部可变状态仍应自行保证线程安全。
- `onEvent` 不能返回 `null`。只使用 `EventService` 的 V2 插件可以使用默认实现，不必再写空方法。
- 不要在 `onEvent` 中长时间阻塞。默认执行超时为 20 秒。
- handler 开始时可读取 `context.cancellationToken()`；异步链必须保存该对象，超时后检查 `isCancellationRequested()` 或调用 `throwIfCancellationRequested()`，不能在其他线程重新读取 ThreadLocal。
- 配置变化、绑定删除、插件重载、数据库热切换和应用停止都可能调用 `stop()`。
- `stop()` 应快速、幂等地释放插件自行创建的资源，且不应抛出异常。

旧插件的 `handlerId()` 仍作为兼容的默认处理器 ID。V2 插件应在 `start(PluginRuntimeContext)` 中通过 `EventService` 注册命名 handler；每个匹配事件会创建独立的 `(event, binding, handlerId)` 投递记录。

## 7. 事件 API

`PluginEvent` 包含：

| 字段 | 含义 |
| --- | --- |
| `id` | Inbox 内部 UUID，可作为业务幂等键 |
| `botId` | 当前绑定的机器人 ID |
| `environment` | `SANDBOX` 或 `PRODUCTION` |
| `eventType` | QQ Gateway dispatch 类型 |
| `platformEventId` | 用于持久化去重的平台事件 ID |
| `rawPayload` | 原始 Gateway JSON，供向前兼容使用 |
| `receivedAt` | Inbox 接收时间 |
| `message` | 能被稳定映射为消息时存在 |

`InboundMessage` 提供回复目标、消息 ID、事件 ID、作者 ID和文本内容。当前稳定映射覆盖：

- `C2C_MESSAGE...` -> `C2C`
- 同时包含 `GROUP` 和 `MESSAGE` -> `GROUP`
- `DIRECT_MESSAGE...` -> `DIRECT`
- 其他消息事件 -> `CHANNEL`

非消息事件、字段缺失或无法解析的载荷会得到 `Optional.empty()`。因此所有插件都必须先检查 `event.message()`，不能假设每个事件都可回复。

`rawPayload` 是兼容逃生口，不是稳定 DTO。直接依赖其中的 QQ 字段时，应容忍字段新增、缺失和未知事件类型。

## 8. 发送文本消息

最安全的被动回复方式是：

```java
return context.messageSender()
        .enqueue(TextMessage.reply(event, "pong"))
        .thenApply(receipt -> null);
```

`TextMessage.reply(...)` 会自动使用原事件的回复目标、`msg_id`/`event_id`、`msg_seq=1`、来源事件 UUID 和稳定去重键。

手工创建 `TextMessage` 时需要遵守：

- 内容不能为空，最多 4000 个 Unicode 字符。
- `messageSequence` 必须大于 0。
- `deduplicationKey` 可选，最大 512 字符且不能含空白。
- 对同一业务副作用使用稳定去重键，重试时不要生成随机键。
- `sourceEventId` 应填入触发消息的 `event.id()`，便于追踪。

`enqueue()` 完成只表示任务已经可靠写入 `outbox_jobs`。返回的 `MessageEnqueueReceipt` 包含任务 ID、是否命中已有去重任务及入队时间；它不表示 QQ 已经接收或发送成功。最终结果应在后台 Outbox/DLQ 中查看。

## 9. 发送富消息与媒体消息

`MessageSender.enqueue(RichMessage)` 支持 `MARKDOWN`、`KEYBOARD`、`ARK` 和 `EMBED`。除 `KEYBOARD` 外，`payload` 是对应 QQ OpenAPI 字段内部的 JSON 对象，宿主会把它放入同名小写字段并补充回复 ID、事件 ID、序号和 C2C/群聊所需的消息类型：

```java
RichMessage markdown = new RichMessage(
        inbound.replyTarget(),
        RichMessageKind.MARKDOWN,
        Map.of("content", "**处理完成**"),
        inbound.messageId(),
        inbound.eventId(),
        1,
        Optional.of("markdown:" + event.id()),
        Optional.of(event.id()));
return context.messageSender().enqueue(markdown).thenApply(receipt -> null);
```

QQ 不支持独立 Keyboard 消息。`KEYBOARD` 是 SDK 提供的组合类型，payload 必须同时包含非空 `markdown` 和 `keyboard` 对象，发送时使用官方 Markdown 类型 `msg_type=2`：

```java
Map<String, Object> payload = Map.of(
        "markdown", Map.of("content", "请选择操作"),
        "keyboard", Map.of("id", "已审核的按钮模板 ID"));
```

自定义按钮使用 `keyboard.content.rows`，其中每个 row 是包含 `buttons` 数组的对象；不要把 row 写成裸按钮数组。Web 机器人页选择 Keyboard 时会填入一个可编辑的完整骨架。

插件可通过 `MediaService`（V2）或兼容的 `MessageSender` 入队远程 `MediaMessage`：

插件可通过 `MediaService`（V2）或兼容的 `MessageSender` 入队 `MediaMessage`：

```java
MediaMessage message = new MediaMessage(
        inbound.replyTarget(),
        MediaKind.IMAGE,
        URI.create("https://cdn.example/image.png"),
        Optional.of("图片说明"),
        inbound.messageId(),
        inbound.eventId(),
        1,
        Optional.of("image:" + event.id()),
        Optional.of(event.id()));

return context.messageSender().enqueue(message).thenApply(receipt -> null);
```

限制如下：

- 只接受公开的 HTTPS URL，最长 2048 字符；每次跳转都会重新校验 DNS 和目标地址。
- 禁止 URL 凭据、fragment、localhost、回环、私网、链路本地和组播目标。
- `C2C` 和 `GROUP` 支持 `IMAGE`、`VIDEO`、`AUDIO`、`FILE`，发送前走 QQ 官方文件预上传。
- `CHANNEL` 和 `DIRECT` 当前只支持 `IMAGE`，通过 multipart `file_image` 发送。
- 宿主会受控下载远程内容并执行机器人级大小上限、重定向和超时检查，不会把 URL 直接交给 QQ 绕过限制。

插件不能传入本地文件路径或自行生成 `file_info`，但声明 `media.send` 后可以把本地字节交给宿主暂存：

```java
StagedMedia staged = context.mediaService().stage(new MediaUpload(
        MediaKind.IMAGE, "result.png", "image/png", imageBytes))
        .toCompletableFuture().join();
return context.mediaService().enqueue(new StagedMediaMessage(
        inbound.replyTarget(), staged, Optional.of("处理结果"),
        inbound.messageId(), inbound.eventId(), 1,
        Optional.of("result:" + event.id()), Optional.of(event.id())))
        .thenApply(receipt -> null);
```

暂存句柄只包含 UUID、类型、文件名和大小，不暴露宿主路径。上传最大值来自当前机器人配置（默认 `16 MiB`，范围 `1-256 MiB`）；MIME 必须与媒体类型匹配，入队和实际发送时会再次核对元数据。Outbox 到达成功、结果未知或死信终态后删除对应暂存文件。

## 10. PluginStorage

声明 `storage` 能力后，可使用按绑定隔离的持久化键值存储：

```java
context.storage().put("settings", "last-user", userId);
Optional<String> value = context.storage().get("settings", "last-user");
Map<String, String> all = context.storage().list("settings");
context.storage().delete("settings", "last-user");
```

约束：

- namespace 最长 64 个字符，key 最长 128 个字符。
- namespace 和 key 必须是非空 token，不能含空白或控制字符。
- value 以 UTF-8 计最多 64 KiB，可保存 JSON 字符串。
- `list(namespace)` 返回该命名空间全部键值的不可变快照；不要把命名空间当作大数据表。
- 存储按绑定 UUID 隔离，同一插件绑定到两个机器人时互不可见。
- 更新绑定配置会保留存储；删除绑定或机器人会级联删除存储。
- 数据库热切换不会迁移插件数据，新数据库使用自己的存储内容。

`PluginStorage` 不提供事务、CAS、扫描游标、TTL 或任意 SQL。需要跨多个键保持严格原子性时，应把状态编码为一个值，或调整业务设计。

## 11. V2 扩展能力

`BotPluginFactoryV2` 的 `create(PluginRuntimeContext)` 会收到以下绑定级能力：

```java
PluginRuntimeContext context = ...;
context.configuration();  // ConfigSnapshot
context.events();         // EventService
context.scheduler();      // PluginScheduler
context.httpClient();     // RestrictedHttpClient
context.mediaService();   // MediaService
context.base();           // 兼容的 PluginContext
```

### EventService 与多 handler

```java
EventSubscription subscription = context.events().subscribe(
        "commands", Set.of("C2C_MESSAGE_CREATE"), this::handleCommand);
```

handler ID 必须是非空、无空白且不超过 128 个字符；同一绑定内不能重复注册。空事件类型集合匹配所有事件。订阅属于绑定资源，宿主停止插件时会自动关闭；插件仍应在 `stop()` 中关闭自己保存的句柄。事件类型不匹配时不会创建投递记录。

### PluginScheduler

```java
PluginTask once = context.scheduler().schedule(Duration.ofSeconds(10), this::refresh);
PluginTask repeated = context.scheduler().scheduleWithFixedDelay(
        Duration.ZERO, Duration.ofMinutes(5), this::refresh);
```

任务回调会进入当前绑定的有界执行队列，不会在 Gateway 或数据库线程执行。`PluginTask.close()` 等价于取消；绑定停止时所有任务都会取消。队列饱和时任务可能被丢弃，插件不应把调度器当作持久化队列。

### RestrictedHttpClient

HTTP 能力只允许公开的 HTTPS URL，禁止 URL 凭据、fragment、重定向、私有/回环/链路本地/组播目标和 `Authorization`、Cookie 等敏感请求头。单次请求超时最多 30 秒，请求体最多 1 MiB，响应体最多 2 MiB。宿主不会向请求添加 QQ 凭据；需要外部服务凭据时应使用专门的服务端中转，而不是把密钥写进插件 JAR。

```java
PluginHttpResponse response = context.httpClient()
        .send(PluginHttpRequest.get(URI.create("https://example.com/status")))
        .toCompletableFuture().join();
```

### MediaService 与 ConfigSnapshot

`MediaService.enqueue(MediaMessage)`、`MediaService.enqueue(StagedMediaMessage)` 与富消息发送都只写入可靠 Outbox，不会把 QQ 凭据、`file_info`、宿主路径或 HTTP 客户端暴露给插件。`ConfigSnapshot` 是创建实例时捕获的不可变 JSON、绑定 revision 和加载时间。配置更新会创建新实例，插件不要修改或缓存可变配置对象。

## 12. 日志

使用 `PluginContext.logger()`，不要依赖宿主的 SLF4J：

```java
context.logger().info("plugin started");
context.logger().warn("configuration fallback used");
context.logger().error("processing failed", exception);
```

宿主会附加 `pluginId` 和 `botId`，移除换行并把单条消息截断为 512 字符。不要记录 AppSecret、Access Token、完整个人信息或完整消息载荷。

## 13. 可靠性与幂等

插件投递采用至少一次处理语义。以下情况都可能使同一事件再次进入 `onEvent`：进程崩溃、执行超时、数据库租约过期、插件抛出异常或返回失败的 CompletionStage。

- 回复消息应使用稳定 `deduplicationKey`；`TextMessage.reply` 已默认提供。
- 其他外部副作用必须由插件自行提供幂等键和幂等接口。
- 不要在完成外部副作用后返回失败，否则宿主会重试整个事件。
- 插件写入 `PluginStorage` 和消息入队不是一个跨资源事务。
- 默认最多尝试 5 次，退避从 1 秒指数增加，上限 300 秒。
- 默认单次插件执行超时 20 秒。宿主会设置取消令牌并中断仍在同步执行的 callback；5 秒宽限期内仍未完成会把绑定隔离为 `QUARANTINED`，阻止新调用能力和后续投递。
- 取消令牌是合作式机制，插件自行创建的线程和网络请求仍必须主动传播取消并保证幂等。

插件配置可通过环境变量调整：

| 环境变量 | 默认值 |
| --- | --- |
| `QQBOT_PLUGINS_ENABLED` | `true` |
| `QQBOT_PLUGINS_DIR` | `/plugins` |
| `QQBOT_PLUGINS_POLL_INTERVAL` | `1s` |
| `QQBOT_PLUGINS_LEASE_DURATION` | `30s` |
| `QQBOT_PLUGINS_EXECUTION_TIMEOUT` | `20s` |
| `QQBOT_PLUGINS_CANCELLATION_GRACE` | `5s` |
| `QQBOT_PLUGINS_MAX_ATTEMPTS` | `5` |
| `QQBOT_PLUGINS_BATCH_SIZE` | `16` |
| `QQBOT_PLUGINS_BINDING_QUEUE_CAPACITY` | `256` |
| `QQBOT_PLUGINS_SHUTDOWN_TIMEOUT` | `20s` |

## 14. 测试

至少覆盖以下场景：

- 非目标事件立即完成且不发送消息。
- 目标消息产生正确回复目标和内容。
- 重复事件使用同一个去重键。
- 缺少 `message`、`content` 或作者字段时不会抛空指针异常。
- 配置合法与非法边界。
- `start`/`stop` 可重复执行，资源能够释放。
- 使用 storage 时验证不同绑定之间隔离。
- 异步失败会通过 CompletionStage 传播，而不是被吞掉。

仓库内的端到端宿主测试位于 `qqbot-plugin-host/src/test/.../Pf4jPluginHostTest.java`，会真实加载示例 JAR、执行事件、检查 Outbox、绑定存储和无重启升级。`qqbot-plugin-testkit` 提供 `PluginTestContext`、消息/媒体/事件/HTTP/存储/日志 fake 和可手动推进的调度器，可直接用于插件单元测试。

构建仓库示例插件：

```powershell
.\gradlew.bat :qqbot-plugin-example:jar `
  "-Dorg.gradle.java.home=E:\JAVA\dragonwell-21.0.11.0.11+10-GA" `
  --no-daemon
```

产物位于 `qqbot-plugin-example/build/libs/qqbot-plugin-echo-*.jar`。

发布可复制 SDK 仓库和完整模板：

```powershell
.\gradlew.bat pluginSdkRepository pluginSdkDistribution `
  "-Dorg.gradle.java.home=E:\JAVA\dragonwell-21.0.11.0.11+10-GA" `
  --no-daemon
```

仓库输出 `build/plugin-sdk/repository` 和 `build/distributions/qqbot-plugin-sdk-*.zip`。复制 `plugin-template` 后通过 `-PqqbotSdkRepository=<repository 路径>` 指定 SDK；最终插件 JAR 中不得包含这些依赖。

## 15. 安装、网页上传与无重启升级

Docker Compose 默认使用 `qqbot-plugins` 命名卷并挂载到容器 `/plugins`。安装步骤：

1. 构建插件 JAR。
2. 将 JAR 放入宿主配置的插件目录或 Docker 插件卷（容器内路径为 `/plugins`）。
3. 在 Web 后台“插件”页执行扫描或重新加载，确认状态为已加载且 SHA-256 符合预期。
4. 创建机器人绑定，填写通过 Schema 校验的 JSON 配置并启用。
5. 在 Inbox、插件投递、Outbox 和 DLQ 页面观察完整链路。

管理员也可以直接在插件页选择 `.jar` 文件。页面会显示文件大小并要求勾选可信来源确认；上传接口是 `POST /api/plugins/upload` 的 multipart `file` 字段，必须带 `X-Plugin-Upload-Confirm: trusted-jar`，单文件上限 64 MiB。请求仍受管理员认证和 CSRF 保护。

上传完成后宿主会在同一进程内校验 Manifest、ServiceLoader、Schema 和 capabilities，停止相关绑定，释放订阅/调度器/HTTP 客户端，卸载旧 ClassLoader，再加载新 JAR。成功后页面显示 `INSTALLED`、`UPGRADED` 或 `UNCHANGED`、旧版本和新 SHA-256；失败会删除候选文件并尝试恢复旧插件，不需要重启应用。升级期间在途任务只等待配置的关闭超时，后续事件使用新实例。

多应用实例部署时，所有实例必须使用相同插件 JAR 和哈希；机器人租约在获取和续租时都会校验活动绑定所需哈希，不一致实例不会继续持有该机器人。上传接口只作用于当前实例，不替代共享制品发布或集群协调。本地媒体暂存目录也必须是所有可能处理该机器人 Outbox 的实例共同挂载的共享目录。

镜像内置的 `echo` 插件可作为部署烟测：`/ping` 回复 `pong`，`/remember` 写入当前绑定自己的存储空间。已有 Docker 命名卷不会因重建镜像自动覆盖同名 JAR。

## 16. 常见故障

| 现象 | 检查项 |
| --- | --- |
| 插件显示但未加载 | Manifest、`Plugin-Class`、Schema 资源、API 版本、未知 capability |
| 报工厂数量错误 | ServiceLoader 文件缺失、类名错误或注册了多个工厂 |
| 插件上传被拒绝 | 文件扩展名/大小、可信确认、管理员会话和服务端校验错误 |
| 插件 ID 不一致 | `Plugin-Id` 与 `BotPluginFactory.pluginId()` 必须相同 |
| 绑定保存失败 | 配置必须是对象且满足 Schema；字段不能超过 65,536 个 Java 字符 |
| 收不到事件 | 机器人 Gateway 状态、Intents、Inbox、绑定启用状态、handler 事件类型和插件投递队列 |
| 能收到但不回复 | 是否声明 `message.send`、事件是否有回复目标、Outbox/DLQ 状态 |
| storage/HTTP/调度器被拒绝 | Manifest 是否声明对应 capability；HTTP 目标和请求头是否触发策略 |
| 重复执行 | 属于至少一次语义；检查去重键及外部副作用幂等性 |
| 新 JAR 未生效 | 核对上传结果、版本和 SHA-256；确认当前请求没有落到旧的多实例副本 |

## 17. 安全边界

PF4J 类加载隔离不是安全沙箱。第一版只允许运维人员部署可信插件 JAR；网页上传会执行插件代码，因此必须限制管理员权限、插件目录和镜像运行身份。不可信第三方插件必须改为独立进程或容器，通过受控 RPC 接入。插件 API 不暴露 Spring、数据库连接、AppSecret 或 Access Token。

## 18. 参考实现

- `plugin-template`：可复制的 V2 项目模板和多 handler 示例。
- `qqbot-plugin-example`：宿主端到端测试使用的最小可运行插件。
- `qqbot-plugin-testkit`：插件单元测试替身。
- `qqbot-plugin-api`：插件可调用的稳定接口。
- `qqbot-plugin-spi`：工厂、生命周期和事件处理契约。
- `qqbot-plugin-host`：仅用于理解宿主行为，插件不得依赖。
- `README.md`：项目总体状态。
- `DEPLOYMENT.md`：Docker Compose、插件卷和运维流程。
