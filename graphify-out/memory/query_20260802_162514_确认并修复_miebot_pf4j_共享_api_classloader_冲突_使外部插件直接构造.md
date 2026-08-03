---
type: "query"
date: "2026-08-02T16:25:14.635832+00:00"
question: "确认并修复 MieBot PF4J 共享 API classloader 冲突，使外部插件直接构造 TextMessage(MessageTarget(...)) 后生成 Outbox"
contributor: "graphify"
outcome: "useful"
source_nodes: ["Pf4jPluginHost", "MessageTarget", "OutboxJob"]
---

# Q: 确认并修复 MieBot PF4J 共享 API classloader 冲突，使外部插件直接构造 TextMessage(MessageTarget(...)) 后生成 Outbox

## Answer

Expanded from original query via graph vocab: [plugin, class, loader, host, api, domain, spi, message, target, outbox]. Confirmed Pf4jPluginHost owns the boundary. A custom PluginClassLoader keeps PDA for plugin-private libraries and resolves com.mieai.qqbot.plugin.api., com.mieai.qqbot.plugin.spi., and com.mieai.qqbot.domain. parent-first. The regression JAR bundles duplicate shared contracts; the stock loader fails with ServiceConfigurationError, while the fix executes TextMessage(MessageTarget(...)) and persists an Outbox text job.

## Outcome

- Signal: useful

## Source Nodes

- Pf4jPluginHost
- MessageTarget
- OutboxJob