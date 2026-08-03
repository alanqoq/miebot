---
type: "query"
date: "2026-08-02T20:30:14.125487+00:00"
question: "Trace the MieAI PF4J outbound failure from class loading to Outbox."
contributor: "graphify"
outcome: "useful"
source_nodes: ["Pf4jPluginHost", "OutboxJob"]
---

# Q: Trace the MieAI PF4J outbound failure from class loading to Outbox.

## Answer

Expanded from graph vocab: [class, delivery, event, host, inbox, message, outbox, plugin, runtime, service]. Pf4jPluginHost contains SharedContractPluginClassLoader; the deployed fix makes kotlin. parent-first, and the focused regression test creates a real OutboxJob when a plugin bundles DefaultConstructorMarker.

## Outcome

- Signal: useful

## Source Nodes

- Pf4jPluginHost
- OutboxJob