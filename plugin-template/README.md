# QQBot 插件模板

这是一个可复制的 Java 21 V2 插件项目。模板只把 `qqbot-plugin-api` 和
`qqbot-plugin-spi` 作为 `compileOnly` 依赖，最终 JAR 不会携带 API、SPI、PF4J、
Spring 或数据库驱动。

## 在本仓库构建

先从仓库根目录发布 SDK 到本地分发仓库：

```powershell
& 'E:\JAVA\dragonwell-21.0.11.0.11+10-GA\bin\java.exe' `
  -classpath '.\.tools\gradle-8.14.3\lib\gradle-launcher-8.14.3.jar' `
  org.gradle.launcher.GradleMain pluginSdkRepository --no-daemon
```

然后构建并运行模板测试（可把整个 `plugin-template` 目录复制到其他项目）：

```powershell
& 'E:\JAVA\dragonwell-21.0.11.0.11+10-GA\bin\java.exe' `
  -classpath '.\.tools\gradle-8.14.3\lib\gradle-launcher-8.14.3.jar' `
  org.gradle.launcher.GradleMain -p .\plugin-template clean test jar `
  --no-daemon
```

复制到其他目录后，通过 `-PqqbotSdkRepository=<SDK 仓库路径>` 或环境变量
`QQBOT_SDK_REPOSITORY` 指定 SDK 仓库。SDK 版本默认是仓库当前的
`0.1.0-SNAPSHOT`。

## 需要改动的文件

- `TemplatePluginFactory.java`：插件 ID、生命周期和事件处理逻辑。
- `META-INF/services/...BotPluginFactory`：保持只注册一个工厂类。
- `qqbot-plugin-schema.json`：绑定配置的 JSON Schema。
- `build.gradle.kts`：Manifest 的 ID、版本和 capabilities。

模板示例使用 `EventService` 注册 `commands` 和 `audit` 两个 handler；不需要
命名 handler 时可保留一个订阅。宿主会为每个匹配的 handler 创建独立投递记录。
`BotPlugin` 的默认 `onEvent` 已完成，因此只使用 `EventService` 时不需要再写空方法。
