# MieBot 框架模块开发指南

本文描述平台 `1.0.8` 的外置框架模块契约。框架模块是放在 `/modules` 中、随应用启动加载的可信 JAR；机器人插件是由 `plugin-support` 从 `/plugins` 加载并绑定到机器人的业务实现。二者不是同一个扩展层。

## 1. 模块与插件边界

| 类型 | 框架模块 | 机器人插件 |
| --- | --- | --- |
| 用途 | 数据库、QQ 运行时、后台、运维、集群等平台能力 | 消息处理、命令、AI 回复等机器人业务 |
| 目录 | `/modules/*.jar` | `/plugins/*.jar` |
| 接口 | `qqbot-module-api`、`qqbot-module-spi` | `qqbot-plugin-api`、`qqbot-plugin-spi` |
| 装配 | 启动前加入应用类路径，随后加载 Spring 自动配置 | 由 `plugin-support` 在运行中加载、上传和绑定 |
| 变更 | 替换 JAR 后重启应用 | 支持受控热升级 |
| Web | JAR 内携带编译后的 Web Component | 通过插件管理页配置，不直接扩展后台壳 |
| 信任边界 | 与核心同进程，拥有完整 JVM/Spring 权限 | 可信 JAR，但只使用插件 API 提供的受控能力 |

框架模块不支持从 Web 上传、热卸载或运行中替换。模块代码拥有完整进程权限，只能安装经过审核的 JAR。`/modules` 在容器内应只读挂载；`/plugins` 因插件上传功能需要可写。

## 2. JAR 格式

一个模块是普通 Kotlin/JVM JAR，但不是 Spring Boot 可执行 JAR。完整结构如下：

```text
reports-1.0.8.jar
├─ META-INF/qqbot/module.json
├─ META-INF/spring/
│  └─ org.springframework.boot.autoconfigure.AutoConfiguration.imports
├─ META-INF/qqbot/modules/reports/web/
│  ├─ main.js
│  └─ assets/...
├─ META-INF/qqbot/modules/reports/db/
│  ├─ sqlite/V001__create_reports.sql
│  ├─ mysql/V001__create_reports.sql
│  └─ postgresql/V001__create_reports.sql
└─ com/example/reports/...
```

必须满足以下规则：

- JAR 中必须且只能有一个 `META-INF/qqbot/module.json`。
- `META-INF/qqbot/modules/<moduleId>/` 下的所有资源必须属于当前描述符 ID，不能替其他模块放置资源。
- 描述符声明的每个 Web 入口文件必须实际存在。
- 一个目录内不能出现重复模块 ID、重复后台路由或重复 Custom Element 名称。
- 模块特有的第三方依赖不能作为嵌套 JAR 放入模块；应使用 shading 并 relocate，或由平台明确提供。

宿主会计算每个 JAR 的 SHA-256，并通过 `GET /api/modules` 返回 `artifact` 和 `sha256`。后台脚本 URL带有摘要版本参数，替换模块并重启后不会继续使用旧缓存。

## 3. 描述符

最小可用的 `module.json`：

```json
{
  "schemaVersion": 1,
  "id": "reports",
  "name": "报表模块",
  "version": "1.0.8",
  "minimumFrameworkVersion": "1.0.0",
  "dependencies": [
    {
      "moduleId": "database-support",
      "minimumVersion": "1.0.8",
      "optional": false
    }
  ],
  "capabilities": ["reports.query"],
  "web": {
    "pages": [
      {
        "id": "overview",
        "label": "报表",
        "route": "/modules/reports/overview",
        "icon": "chart",
        "order": 60,
        "entrypoint": "main.js",
        "customElement": "qqbot-reports-overview"
      }
    ]
  }
}
```

- `schemaVersion` 当前只能是 `1`。
- `id` 发布后不能修改；只能使用小写字母、数字及分隔符 `.`、`_`、`-`。
- 版本字段使用语义化版本 `major.minor.patch`。
- 必需依赖缺失、目标版本过低、依赖成环或框架版本不满足时，Spring 创建模块 Bean 前即拒绝启动。
- 可选依赖缺失不阻止启动；存在时仍参与版本和拓扑排序。
- 页面路由必须严格为 `/modules/{moduleId}/{pageId}`。

当前默认模块依赖关系为：

| 模块 ID | 职责 | 必需依赖 |
| --- | --- | --- |
| `database-support` | SQLite/MySQL/PostgreSQL、迁移和在线配置 | 无 |
| `qqbot-runtime` | QQ Gateway/OpenAPI、机器人监督、可靠消息和媒体 | `database-support` |
| `platform-admin` | 登录、首次设置、系统接口和后台壳 | `database-support`、`qqbot-runtime` |
| `plugin-support` | 机器人插件加载、上传、绑定、每 bot/plugin 文件目录和投递 | 前三个模块 |
| `operations` | 健康、Dashboard、审计和队列查询 | 前四个模块 |
| `cluster-support` | SQL 租约、fencing 和插件一致性 | `database-support`、`qqbot-runtime`、`plugin-support` |
| `onebot11` | C2C/普通群 OneBot 11 WebSocket 兼容层 | `database-support`、`qqbot-runtime` |

`plugin-support` 的描述符通过 `plugin.binding-files` 声明绑定文件管理能力；该能力包含每个 `/data/plugin-data/<botId>/<pluginId>/` 目录的初始化、插件所选 `config.json`/`config.yml`/`config.yaml` 校验、管理 API 和后台文件管理器。它描述模块提供的功能，不代表 PF4J 插件运行在文件系统安全沙箱中。

## 4. Spring 后端

模块至少通过 SDK 编译，并把平台已提供的库声明为 `compileOnly`：

```kotlin
dependencies {
    compileOnly("com.mieai.qqbot:qqbot-module-api:1.0.8")
    compileOnly("com.mieai.qqbot:qqbot-module-spi:1.0.8")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    compileOnly("org.springframework.boot:spring-boot-starter-web:3.5.16")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.16")
}
```

平台 Boot JAR 已提供 Spring Boot、Jackson、Jackson Kotlin、Kotlin 1.9.25 标准库与反射库、JDBC、Flyway、SQLite/MySQL/PostgreSQL 驱动，以及本仓库的 domain、protocol、client、gateway、runtime、persistence、admin 和插件宿主基础库。不要把这些库重复打入模块 JAR。

模块使用标准 Spring Boot 自动配置：

```kotlin
@AutoConfiguration
@ConditionalOnProperty(
    name = ["qqbot.modules.available.reports"],
    havingValue = "true",
)
@ComponentScan(basePackageClasses = [ReportsModuleMarker::class])
class ReportsAutoConfiguration
```

在以下文件中登记类名：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

```text
com.example.reports.ReportsAutoConfiguration
```

Controller、Service、配置属性和其他 Bean 都可以由该自动配置导入。组件扫描范围必须只覆盖本模块拥有的包，不能扫描 `com.mieai.qqbot` 或其他模块的上层包。

## 5. 生命周期与模块间调用

没有特殊生命周期代码时，宿主会直接根据 JSON 描述符创建声明式模块。需要在所有 Spring Bean 就绪后执行启动逻辑时，声明 `FrameworkModuleLifecycle` Bean：

```kotlin
@Bean
fun reportsLifecycle(reports: ReportQueryService): FrameworkModuleLifecycle = object : FrameworkModuleLifecycle {
    override val moduleId: String = "reports"

    override fun start(context: ModuleContext) {
        context.publish(ReportServices.QUERY, reports)
    }

    override fun stop() {
        // 释放模块自己持有的资源；实现应快速且幂等。
    }
}
```

模块按描述符依赖拓扑启动，停机和失败回滚时反序停止。生命周期 Bean 的 `moduleId` 必须对应同目录中的一个 JAR 描述符。

跨模块稳定调用使用 `ModuleServiceKey<T>`：

```kotlin
object ReportServices {
    val QUERY: ModuleServiceKey<ReportQueryService> =
        ModuleServiceKey("reports.query", ReportQueryService::class.java)
}
```

消费方必须先在 `module.json` 声明对提供方的依赖，然后在自己的 `start()` 中调用 `context.require(ReportServices.QUERY)`。未声明依赖的服务访问、重复服务键和已停止模块的服务都会被拒绝。共享服务接口应位于双方都能编译的稳定契约制品中，不能暴露实现类。

## 6. Web 后台页面

后台壳只负责认证、导航、主题和挂载。活动模块的页面来自 `/api/modules`，入口脚本由 `/module-assets/{moduleId}/...` 从所属 JAR 精确读取，不通过共享 ClassLoader 猜测资源所有者。

入口脚本必须注册描述符中的标准 Custom Element：

```js
class ReportsOverview extends HTMLElement {
  connectedCallback() {
    this.innerHTML = '<section><h1>报表</h1></section>';
  }
}

customElements.define('qqbot-reports-overview', ReportsOverview);
```

可以使用 Angular、React、Vue 或原生 Web Component，但必须输出浏览器可直接加载的 ESM。当前内置模块使用独立 Angular application bundle，把原有业务页面注册为 Custom Element 后收纳进各自 JAR。一个模块可以在同一个 `main.js` 中注册多个页面元素。

壳应用在元素上设置：

```html
<qqbot-reports-overview
  module-id="reports"
  contribution-id="overview">
</qqbot-reports-overview>
```

页面可以使用同源 API，也可以使用壳提供的 `window.qqbot`：

```ts
await window.qqbot.request('/api/reports', { method: 'GET' });
await window.qqbot.navigate('/modules/reports/overview');
window.qqbot.notify({ level: 'success', message: '报表已更新' });
const accepted = await window.qqbot.confirm('确认执行？');
const surface = window.qqbot.themeToken('--surface');
```

`request()` 强制同源路径、携带会话 Cookie，并为非安全方法补充 CSRF Header；模块也可以使用正确配置 XSRF 的框架 HTTP 客户端。页面脚本拥有与后台相同的浏览器权限，因此模块仍属于可信代码。

### 6.1 机器人设置扩展槽

模块可以通过 `web.botSettings` 把 Web Component 挂到每个现有机器人的编辑区域，而不需要修改后台壳：

```json
{
  "web": {
    "pages": [],
    "botSettings": [
      {
        "id": "reports-bot-settings",
        "label": "机器人报表",
        "order": 100,
        "entrypoint": "main.js",
        "customElement": "qqbot-reports-bot-settings"
      }
    ]
  }
}
```

入口文件同样放在 `META-INF/qqbot/modules/<moduleId>/web/`。宿主会校验资源存在、`id` 合法、`order` 在 `0-100000` 范围内，并保证页面与设置扩展的 Custom Element 名称全局唯一。`GET /api/modules` 在活动模块的 `botSettingsContributions` 中返回解析后的入口 URL 和 SHA-256 缓存版本。

后台壳按 `order`、`label` 排序并加载元素，只设置当前机器人的稳定 UUID：

```html
<qqbot-reports-bot-settings bot-id="8f6f...">
</qqbot-reports-bot-settings>
```

设置组件自行调用同源后端 API、处理加载/保存/冲突状态，并且不得读取机器人 AppSecret。模块如保存敏感设置，应复用平台密钥体系加密，响应中只能返回是否已配置。机器人删除后的模块数据应通过指向 `bots(id)` 的 `ON DELETE CASCADE` 或等价受控清理移除。

## 7. 模块数据库迁移

模块迁移放在：

```text
META-INF/qqbot/modules/<moduleId>/db/<sqlite|mysql|postgresql>/V001__description.sql
```

`database-support` 会在初始数据库、候选数据库验证和在线切换时执行活动模块的迁移。每个模块使用独立的 Flyway 历史表，因此多个模块都可以从 `V001` 开始。迁移仍必须满足三种数据库的 SQL 行为边界；缺少某个方言目录表示该模块没有该方言迁移，而不是自动转换 SQL。

模块删除后不会自动删除其表或 Flyway 历史。破坏性清理必须由显式迁移或运维步骤完成。

## 8. 构建与部署

根项目生成模块 SDK：

```powershell
& 'E:\JAVA\dragonwell-21.0.61.0.61+10-GA\bin\java.exe' `
  -classpath '.\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain `
  moduleSdkRepository moduleSdkDistribution `
  --no-configuration-cache --no-daemon
```

本仓库的页面必须先构建，再生成默认模块 JAR：

```text
cd qqbot-admin-web
npm ci
npm run build
cd ..
./gradlew defaultModuleDirectory defaultModuleDistribution
```

输出位置：

- `build/runtime/modules/*.jar`
- `build/distributions/qqbot-default-modules-1.0.8.zip`
- `build/distributions/qqbot-module-sdk-1.0.8.zip`

源码 Compose 运行通常不需要预先执行 `stageRuntimeExtensions`；`qqbot-prepare`
会在 `modules/` 或 `plugins/` 没有 JAR 时从刚构建的镜像初始化默认制品。
只有需要在宿主机预先准备制品时，才执行：

```text
./gradlew stageRuntimeExtensions
docker compose up -d --build
```

离线构建 `Dockerfile.cached-runtime` 时，还要另外准备 `qqbot-app.jar`，并显式
使用 `compose.cached-runtime.yaml`。

Debian 目录约定：

```text
/opt/qqbot/
├─ compose.yaml
├─ compose.cached-runtime.yaml  (optional offline build)
├─ modules/*.jar
├─ plugins/*.jar
└─ config/
```

`compose.yaml` 将 `./modules` 挂载到 `/modules:ro`。增加、删除或替换模块后执行 `docker compose restart qqbot`；启动校验失败时查看日志并恢复上一套完整模块目录。不要把模块 JAR 放入 `/plugins`，也不要把机器人插件放入 `/modules`。

## 9. 发布检查

- `module.json` 的 ID、版本、依赖、页面和实际代码一致。
- JAR 是普通单体 JAR，平台库使用 `compileOnly`，额外库已 shading/relocate。
- 自动配置只扫描模块自己的包。
- 必需依赖缺失、版本过低和依赖环测试会在启动前失败。
- Web 入口、所有分块和静态资源均在本模块命名空间内。
- 三种数据库的模块迁移按实际支持范围测试。
- 替换 JAR 后通过 `/api/modules` 核对 `ACTIVE`、版本、文件名和 SHA-256。
- 机器人插件仍通过 `plugin-support` 开发、上传和绑定，不登记为框架模块。
