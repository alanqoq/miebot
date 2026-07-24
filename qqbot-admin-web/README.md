# QQ Bot Admin Web

这是 QQ 机器人平台的 Angular 22.0.7 管理后台。它构建后台应用壳，以及 `operations`、`qqbot-runtime`、`plugin-support`、`platform-admin`、`onebot11` 五个框架模块的 Web Component 页面包；默认运行时模块总数仍为七个。

## 环境

- Node.js：`^22.22.3`、`^24.15.0` 或 `>=26.0.0`。
- npm：项目声明 `npm@11.9.0`；使用 `npm ci` 安装锁定依赖。

## 常用命令

在本目录运行：

```powershell
npm.cmd ci
npm.cmd run build
npm.cmd test -- --watch=false
npm.cmd start
```

`npm.cmd run build` 会构建应用壳和全部五个模块页面包。开发服务器只服务后台应用壳，默认地址为 `http://localhost:4200/`。

当前仓库使用 Angular/Vitest 单元测试，未配置 `ng e2e` 目标或端到端测试框架。管理 API 客户端由 `src/app/core/` 下的服务手工维护，不由 OpenAPI 自动生成。
