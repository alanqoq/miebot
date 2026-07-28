# OneBot 11 兼容范围

本项目的 `onebot11` 框架模块把 QQ 官方机器人 API 的 C2C 和普通群能力转换为 [OneBot 11](https://github.com/botuniverse/onebot-11) WebSocket 接口。它是兼容子集，不模拟个人 QQ 客户端，也不会把 QQ 频道、子频道、频道私信、论坛、频道成员、身份组、频道权限、公告、精华、日程、音频频道或频道互动事件转换成 OneBot 事件。

该模块只用于让外部第三方程序通过正向或反向 WebSocket 接入本平台。它是 `/modules` 下的框架模块，不是 `/plugins` 下的 PF4J 机器人插件，不参与插件加载、绑定、生命周期或 Plugin API，也不能作为机器人插件的开发依赖。本项目的插件开发文档只描述原生插件 SDK，明确不包含 OneBot 接入方式；插件作者应使用 `PluginEvent`、`EventService` 和 `MessageSender` 等原生接口。

## 1. 启用与传输

进入管理后台的机器人编辑页，在“OneBot 11”区域配置。新机器人默认不启用；启用时必须至少选择一种 WebSocket 模式并设置 access token。

| 能力 | 当前实现 |
| --- | --- |
| 正向 WebSocket | 支持 `/api`、`/event` 和 Universal `/`，路径末尾 `/` 也可接受 |
| 正向鉴权 | 支持 `Authorization: Bearer <token>`，也支持 URL 查询参数 `access_token` |
| 反向 WebSocket | 支持一条 Universal 连接；URL 中包含协议、主机、端口和路径 |
| 反向请求头 | 发送 `Authorization: Bearer <token>`、`X-Self-ID`、`X-Client-Role: Universal` |
| 断线重连 | 支持配置固定重连间隔，范围 `500-300000 ms` |
| 元事件 | 建连后发送 `lifecycle/connect`；可选心跳，范围 `1000-300000 ms` |
| HTTP API / HTTP POST 上报 | 本期不支持 |

正向和反向共用同一个 access token。令牌使用平台主密钥进行 AES-GCM 加密，读取接口和页面只返回“是否已配置”，不会返回明文。留空表示保留已有令牌；未设置令牌时不能启用传输。

OneBot 传输只在当前实例实际运行该机器人 QQ Gateway 时启动。多实例部署不会为同一个机器人重复监听或重复建立反向连接；租约转移后由新持有者启动传输。

## 2. 标识符兼容方式

QQ 官方 API 使用字符串 OpenID，而 OneBot 11 使用数字 `self_id`、`user_id` 和 `group_id`。模块为每个机器人生成持久化数字别名：

- `self_id` 对应该机器人的 AppID 别名，不是真实 QQ 号。
- C2C 用户、群、群内成员分别建立数字别名；同一成员在不同群中的别名可以不同。
- 只有已经从 QQ 事件中观察并写入映射的用户或群，才能作为发送目标或 `at` 目标。
- OneBot `message_id` 是持久化的正 `int32` 别名，用来关联 QQ 官方消息 ID。
- 别名只在同一机器人、同一数据库内稳定。不要把它当作可跨部署交换的 QQ 号；迁移时必须保留 OneBot 模块表。

这是 QQ 官方 OpenID 模型与传统 OneBot QQ 号模型之间无法消除的差异。

## 3. 已支持 Action

| Action | 兼容行为和限制 |
| --- | --- |
| `send_private_msg` | 转为 QQ C2C 发送；`user_id` 必须已有映射 |
| `send_group_msg` | 转为 QQ 普通群发送；`group_id` 必须已有映射 |
| `send_msg` | 支持 `private`、`group`，也可由 `user_id`/`group_id` 推断 |
| `delete_msg` | 用本地映射的 QQ 消息 ID 调用官方撤回；仍受 QQ 时限和权限限制 |
| `get_msg` | 返回本模块已接收或已发送并持久化的消息，不向 QQ 远程查询历史记录 |
| `get_login_info` | 返回持久化的合成 `self_id` 和机器人显示名 |
| `get_image` | 下载入站图片段中的公开 HTTPS URL，返回服务端本地缓存路径 |
| `get_record` | 下载公开 HTTPS URL；仅当源扩展名与 `out_format` 相同，不执行 FFmpeg 转码 |
| `can_send_image` | 返回 `yes=true`；实际发送仍受 QQ 权限、格式、大小和配额限制 |
| `can_send_record` | 返回 `yes=true`；实际发送仍受 QQ 权限、格式、大小和配额限制 |
| `get_status` | `online/good` 取自该机器人 QQ Gateway 是否为 `ONLINE` |
| `get_version_info` | 返回实现版本、OneBot `v11` 和兼容子集标识 |
| `set_restart` | 异步重启该机器人的 OneBot 正向/反向传输，不重启 JVM 或 QQ Gateway |
| `clean_cache` | 删除该机器人由 `get_image/get_record` 产生的本地缓存 |

上述 action 支持标准 `echo` 原样返回，也接受 `_async` 和 `_rate_limited` 后缀。`_rate_limited` 以全模块固定 `500 ms` 间隔调度。`set_restart` 按 OneBot 要求返回异步状态；当前仅重启 transport，`delay` 参数不改变调度时间。

请求 JSON 最大 `1 MiB`。同步 QQ 调用最多等待 30 秒；参数错误返回 `failed/1400`，不支持的 action 返回 `failed/1404`，QQ 调用失败返回 `failed/1200`。带 `_async` 或 `_rate_limited` 后缀的未知 action 同样直接返回 `1404`。

## 4. 暂不支持的标准 Action

以下 OneBot 11 公开 action 均明确返回 `failed/1404`：

| 范围 | Action | 原因 |
| --- | --- | --- |
| 合并转发与互动 | `get_forward_msg`、`send_like` | QQ 官方 C2C/普通群接口没有等价的 OneBot 语义 |
| 普通群管理 | `set_group_kick`、`set_group_ban`、`set_group_anonymous_ban`、`set_group_whole_ban`、`set_group_admin`、`set_group_anonymous`、`set_group_card`、`set_group_name`、`set_group_leave`、`set_group_special_title` | 不能用 QQ 频道管理接口冒充普通群操作 |
| 请求处理 | `set_friend_add_request`、`set_group_add_request` | 当前 QQ 事件没有可与 OneBot `flag` 往返对应的请求处理链路 |
| 资料与列表 | `get_stranger_info`、`get_friend_list`、`get_group_info`、`get_group_list`、`get_group_member_info`、`get_group_member_list`、`get_group_honor_info` | QQ 官方 C2C/普通群未提供满足 OneBot 字段和枚举语义的查询能力 |
| 客户端凭证 | `get_cookies`、`get_csrf_token`、`get_credentials` | QQ 官方机器人使用 App Access Token，不存在个人 QQ Cookie/CSRF；模块不会暴露平台凭据 |

OneBot 隐藏 API、快速操作 API 和实现私有 action 也不支持。以后只有在 QQ 官方提供等价能力且能保持权限、安全和返回语义时才应增加，不能返回伪造的空列表或假成功。

## 5. 消息段

发送时同时接受 CQ 字符串和 OneBot 数组格式，字符串的 `auto_escape=true` 会按纯文本处理。最多 64 个消息段、合计最多 32768 个字符。

| 消息段 | 发送 | 接收 | 限制 |
| --- | --- | --- | --- |
| `text` | 支持 | 支持 | QQ 原始内容作为文本段 |
| `face` | 支持 | 不拆分 | 发送时转换为 QQ `<emoji:id>` 文本标记 |
| `at` | 支持 | 不拆分 | 仅普通群；目标必须是同一群中已映射成员；不支持 `qq=all` |
| `reply` | 支持 | 支持 | 引用消息必须已映射且属于同一会话 |
| `image` | 支持 | 支持 | 发送只接受公开 HTTPS 或 `base64://`；不支持本地文件、HTTP、闪照参数 |
| `record` | 支持 | 支持 | 发送只接受公开 HTTPS 或 `base64://`；不支持变声参数和格式转换 |
| `video` | 支持 | 支持 | 发送只接受公开 HTTPS 或 `base64://` |

每条发送消息最多包含一个媒体段，可同时带文本和一个媒体段。Base64 输入上限为 256 MiB，最终仍受机器人媒体上限和 QQ 接口限制；远程 URL 使用 HTTPS、重定向、大小和私网地址检查。

`rps`、`dice`、`shake`、`poke`、`anonymous`、`share`、`contact`、`location`、`music`、`forward`、`node`、`xml`、`json` 等消息段不支持发送，使用时返回参数错误，不会降级成伪造文本。

## 6. 已支持事件

事件只在 QQ Gateway Dispatch 已成功写入 Inbox 且首次去重插入后，才异步发布给 OneBot transport。晚订阅不重放历史事件；实时订阅队列溢出时事件仍保留在 Inbox，但不会补发给 OneBot 客户端。

| QQ Gateway 事件 | OneBot 11 事件 |
| --- | --- |
| `C2C_MESSAGE_CREATE` | `message/private/friend` |
| `GROUP_AT_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE` | `message/group/normal` |
| `FRIEND_ADD` | `notice/friend_add` |
| `GROUP_ADD_ROBOT` | `notice/group_increase`，`user_id=self_id` |
| `GROUP_DEL_ROBOT` | `notice/group_decrease/kick_me` |
| `GROUP_MEMBER_ADD` | `notice/group_increase`，尽力区分 `approve/invite` |
| `GROUP_MEMBER_REMOVE` | `notice/group_decrease`，尽力区分 `leave/kick` |
| WebSocket 建连 | `meta_event/lifecycle/connect` |
| 配置的定时心跳 | `meta_event/heartbeat` |

普通群消息的 QQ `author.member_role` 会映射为 OneBot `sender.role`，支持 `member`、`admin` 和 `owner`；字段缺失或出现未知值时回退为 `member`。发送者昵称、群名片和操作者等字段按 QQ Payload 尽力填充；QQ 未提供的性别、年龄、地区、等级、头衔等使用 OneBot 允许的未知/空值。入站附件按 MIME 类型映射为 `image`、`record` 或 `video`。

暂不转换群文件、管理员变动、禁言、消息撤回、戳一戳、红包运气王、群荣誉，以及好友/加群请求事件。所有 QQ 频道及频道扩展事件也不转换，但它们仍可由平台 Inbox、强类型 QQ 事件 DTO 和插件能力消费。

## 7. Docker 端口与缓存

反向 WebSocket 是容器主动出站连接，不需要新增入站端口。URL 中的 `localhost` 指容器自身；连接宿主服务可使用 Compose 已配置的 `host.docker.internal`，跨主机应使用可路由地址和 `wss://`。

正向 WebSocket 的监听端口来自每个机器人配置，Docker 无法根据数据库配置自动发布。需要在 `compose.yaml` 的 `ports` 中逐个声明，并在机器人页把监听地址设为 `0.0.0.0`：

```yaml
ports:
  - "${QQBOT_PUBLISHED_ADDRESS:-0.0.0.0}:${QQBOT_PUBLISHED_PORT:-8080}:8080"
  - "5700:5700"
```

多个机器人使用正向模式时必须使用不同容器端口，并逐项发布。不要把无鉴权的 OneBot 端口暴露到公网；本模块即使在内网也要求 access token。

`get_image/get_record` 缓存由 `QQBOT_ONEBOT11_CACHE_DIRECTORY` 控制，Compose 默认使用 `/data/onebot-cache`。`clean_cache` 只清理对应机器人子目录。
