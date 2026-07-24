# Changelog

本文件记录面向使用者的发布变更。插件 API 兼容级别与 Maven 制品版本分别维护；请同时阅读对应 SDK 指南。

## 0.4.1 - 2026-07-24

### Changed

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
