# 插件开发指南

本文档描述当前代码已经实现并可使用的插件接口。需求文档中的未来规划不等同于现有 API；开发插件时应以本文件、`qqbot-plugin-api` 和 `qqbot-plugin-spi` 的源码为准。

## 1. 运行模型

插件是由运维人员部署的可信 JAR。宿主使用 PF4J 加载 JAR，但插件作者只实现纯 Java SPI，不需要依赖 PF4J 或 Spring。

```text
QQ Gateway 事件
  -> event_inbox 持久化和去重
  -> plugin_deliveries 独立投递
  -> BotPlugin.onEvent(...)
  -> MessageSender 写入 outbox_jobs
  -> Outbox Worker 调用 QQ OpenAPI
```

- 一个 JAR 对应一个插件制品和一个 ClassLoader。
- 一个插件可以绑定多个机器人。
- 每个绑定创建独立的 `BotPlugin` 实例、配置和 `PluginStorage` 空间。
- 配置或启用状态变化时，旧实例停止，后续事件使用新实例。
- 插件异常或超时会触发有限重试，超过上限进入插件 DLQ。
- PF4J 类加载隔离不是安全沙箱，只能部署可信插件。

## 2. 开发环境

插件需要 Java 21。仓库内开发时，只依赖以下两个模块：

```kotlin
dependencies {
    compileOnly(project(":qqbot-plugin-api"))
    compileOnly(project(":qqbot-plugin-spi"))
}
```

外部插件项目应依赖与宿主完全相同版本的 `qqbot-plugin-api` 和 `qqbot-plugin-spi`。当前 API 版本为 `1.0.0`。这些模块目前没有发布到公共 Maven 仓库；独立项目可以先构建本仓库的 `qqbot-domain`、`qqbot-plugin-api` 和 `qqbot-plugin-spi` JAR，再把三者作为仅编译依赖，或者直接在本仓库中增加插件子模块。

必须使用 `compileOnly` 或 Maven 的 `provided` scope。不要把 API/SPI、PF4J、Spring、数据库驱动或宿主模块打入插件 JAR，否则可能出现类型不相等、类加载冲突或越过宿主安全边界的问题。

仓库内可复制 `qqbot-plugin-example` 作为起点。建议目录如下：

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
| `Plugin-Requires` | 建议 | 宿主插件 API 兼容版本；缺省时宿主按当前 `1.0.0` 处理 |
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
            "Plugin-Requires" to "1.0.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Capabilities" to "event.read,message.send,storage",
        )
    }
}
```

当前只接受三种能力：

| 能力 | 行为 |
| --- | --- |
| `event.read` | 接收事件；未声明时宿主会自动补充 |
| `message.send` | 使用 `MessageSender` 写入可靠 Outbox |
| `storage` | 使用绑定级 `PluginStorage` |

声明任何其他能力都会导致插件校验失败，插件不会进入已加载状态。未声明 `message.send` 时，发送调用返回失败的 `CompletionStage`；未声明 `storage` 时，存储调用会同步抛出 `SecurityException`。

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

`BotPlugin` 提供三个生命周期方法：

```java
default void start(PluginContext context) {}
CompletionStage<Void> onEvent(PluginEvent event);
default void stop() {}
```

- 插件绑定实例按需创建：第一条待处理事件到来时执行 `create(context)`，随后立即调用 `start(context)`。
- 每个绑定复用同一个插件实例处理事件。
- 当前生产投递循环逐个等待任务完成，但宿主使用共享执行线程池，未来调度方式也可能变化；插件不应把串行执行当作 API 保证，内部可变状态必须自行保证线程安全。
- `onEvent` 不能返回 `null`，必须返回代表全部处理完成的 `CompletionStage<Void>`。
- 不要在 `onEvent` 中长时间阻塞。默认执行超时为 20 秒。
- 配置变化、绑定删除、插件重载、数据库热切换和应用停止都可能调用 `stop()`。
- `stop()` 应快速、幂等地释放插件自行创建的资源，且不应抛出异常。

`handlerId()` 当前不会用于生成多个处理器投递；生产投递处理器固定为 `default`。不要依赖自定义 `handlerId()` 获得多处理器语义。

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

## 9. 发送媒体消息

插件可通过同一个 `MessageSender` 入队 `MediaMessage`：

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

- 只接受公开的 HTTPS URL，最长 2048 字符。
- 禁止 URL 凭据、fragment、localhost、回环地址、私有 IP、链路本地和组播 IP 字面量。
- `C2C` 和 `GROUP` 支持 `IMAGE`、`VIDEO`、`AUDIO`、`FILE`，发送前走 QQ 官方文件预上传。
- `CHANNEL` 和 `DIRECT` 当前只支持 `IMAGE` URL。
- 不支持本地文件路径、字节流或插件自行上传后传递 `file_info`。

应用不会下载媒体 URL，而是把 URL提交给 QQ OpenAPI。构造消息时只会拒绝明显不安全的 URL；QQ 服务器取用 URL 时的可访问性、重定向、文件大小、机器人权限和平台格式限制仍可能使 Outbox 任务失败或进入 DLQ。

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

## 11. 日志

使用 `PluginContext.logger()`，不要依赖宿主的 SLF4J：

```java
context.logger().info("plugin started");
context.logger().warn("configuration fallback used");
context.logger().error("processing failed", exception);
```

宿主会附加 `pluginId` 和 `botId`，移除换行并把单条消息截断为 512 字符。不要记录 AppSecret、Access Token、完整个人信息或完整消息载荷。

## 12. 可靠性与幂等

插件投递采用至少一次处理语义。以下情况都可能使同一事件再次进入 `onEvent`：进程崩溃、执行超时、数据库租约过期、插件抛出异常或返回失败的 CompletionStage。

- 回复消息应使用稳定 `deduplicationKey`；`TextMessage.reply` 已默认提供。
- 其他外部副作用必须由插件自行提供幂等键和幂等接口。
- 不要在完成外部副作用后返回失败，否则宿主会重试整个事件。
- 插件写入 `PluginStorage` 和消息入队不是一个跨资源事务。
- 默认最多尝试 5 次，退避从 1 秒指数增加，上限 300 秒。
- 默认单次插件执行超时 20 秒，超时任务可能仍在插件自己的异步线程中继续运行，因此插件应支持取消或幂等完成。

插件配置可通过环境变量调整：

| 环境变量 | 默认值 |
| --- | --- |
| `QQBOT_PLUGINS_ENABLED` | `true` |
| `QQBOT_PLUGINS_DIR` | `/plugins` |
| `QQBOT_PLUGINS_POLL_INTERVAL` | `1s` |
| `QQBOT_PLUGINS_LEASE_DURATION` | `30s` |
| `QQBOT_PLUGINS_EXECUTION_TIMEOUT` | `20s` |
| `QQBOT_PLUGINS_MAX_ATTEMPTS` | `5` |
| `QQBOT_PLUGINS_BATCH_SIZE` | `16` |

## 13. 测试

至少覆盖以下场景：

- 非目标事件立即完成且不发送消息。
- 目标消息产生正确回复目标和内容。
- 重复事件使用同一个去重键。
- 缺少 `message`、`content` 或作者字段时不会抛空指针异常。
- 配置合法与非法边界。
- `start`/`stop` 可重复执行，资源能够释放。
- 使用 storage 时验证不同绑定之间隔离。
- 异步失败会通过 CompletionStage 传播，而不是被吞掉。

仓库内的端到端宿主测试位于 `qqbot-plugin-host/src/test/.../Pf4jPluginHostTest.java`，会真实加载示例 JAR、执行事件、检查 Outbox 和绑定存储。当前 `qqbot-plugin-testkit` 模块尚未提供现成 fake 或 fixture 类，外部插件暂时需要自行构造 `PluginContext` 测试替身。

构建仓库示例插件：

```powershell
.\gradlew.bat :qqbot-plugin-example:jar `
  "-Dorg.gradle.java.home=E:\JAVA\dragonwell-21.0.11.0.11+10-GA" `
  --no-daemon
```

产物位于 `qqbot-plugin-example/build/libs/qqbot-plugin-echo-*.jar`。

若在仓库外开发且尚未发布依赖，可先在本仓库执行：

```powershell
.\gradlew.bat :qqbot-domain:jar :qqbot-plugin-api:jar :qqbot-plugin-spi:jar `
  "-Dorg.gradle.java.home=E:\JAVA\dragonwell-21.0.11.0.11+10-GA" `
  --no-daemon
```

然后在插件项目中把这三个 JAR 放入 `libs/`，并使用 `compileOnly(files(...))` 引用；最终插件 JAR 中不得包含这些依赖。

## 14. 安装与更新

Docker Compose 默认使用 `qqbot-plugins` 命名卷并挂载到容器 `/plugins`。安装步骤：

1. 构建插件 JAR。
2. 将 JAR 放入宿主配置的插件目录或 Docker 插件卷（容器内路径为 `/plugins`）。
3. 在 Web 后台“插件”页执行重新加载，确认状态为已加载且 SHA-256 符合预期。
4. 创建机器人绑定，填写通过 Schema 校验的 JSON 配置并启用。
5. 在 Inbox、插件投递、Outbox 和 DLQ 页面观察完整链路。

替换已有 JAR 前应先停用相关绑定。插件升级当前允许要求受控重启；虽然后台提供重新加载操作，但不要把它视为任意版本升级下可靠的 ClassLoader 热升级协议。多应用实例部署时，所有实例必须使用相同插件 JAR 和哈希。

镜像内置的 `echo` 插件可作为部署烟测：`/ping` 回复 `pong`，`/remember` 写入当前绑定自己的存储空间。已有 Docker 命名卷不会因重建镜像自动覆盖同名 JAR。

## 15. 常见故障

| 现象 | 检查项 |
| --- | --- |
| 插件显示但未加载 | Manifest、`Plugin-Class`、Schema 资源、API 版本、未知 capability |
| 报工厂数量错误 | ServiceLoader 文件缺失、类名错误或注册了多个工厂 |
| 插件 ID 不一致 | `Plugin-Id` 与 `BotPluginFactory.pluginId()` 必须相同 |
| 绑定保存失败 | 配置必须是对象且满足 Schema；字段不能超过 65,536 个 Java 字符 |
| 收不到事件 | 机器人 Gateway 状态、Intents、Inbox、绑定启用状态和插件投递队列 |
| 能收到但不回复 | 是否声明 `message.send`、事件是否有回复目标、Outbox/DLQ 状态 |
| storage 抛 SecurityException | Manifest 是否声明 `storage` |
| 重复执行 | 属于至少一次语义；检查去重键及外部副作用幂等性 |
| 新 JAR 未生效 | 插件卷中可能仍是旧文件；核对后台 SHA-256 并重新加载或重启 |

## 16. 当前未开放能力

以下能力出现在需求规划中，但当前插件 API 尚未实现，不能在插件中使用：

- PluginScheduler
- 受限 HTTP Client
- 独立 MediaService
- EventService 订阅接口
- 配置对象自动绑定或动态 ConfigSnapshot
- 多 handler 投递
- Web 上传并执行插件 JAR
- 不可信插件沙箱

需要访问外部 HTTP、定时任务或其他宿主资源时，不要绕过边界直接依赖 Spring 或数据库；应先在 `qqbot-plugin-api` 中设计受控能力，再由宿主提供实现。

## 17. 参考实现

- `qqbot-plugin-example`：最小可运行插件、Manifest、Schema 和 ServiceLoader 文件。
- `qqbot-plugin-api`：插件可调用的稳定接口。
- `qqbot-plugin-spi`：工厂、生命周期和事件处理契约。
- `qqbot-plugin-host`：仅用于理解宿主行为，插件不得依赖。
- `README.md`：项目总体状态。
- `DEPLOYMENT.md`：Docker Compose、插件卷和运维流程。
