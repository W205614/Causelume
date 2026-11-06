# 运维控制台

默认入口使用 Java 同源 API 和会话 Cookie。登录、服务、告警、工单、用户及审计接入业务数据库。工单负责人或管理员可在已分派、处理中状态发起调查。任务支持手动刷新状态、核验证据来源和提交人工反馈。服务目录中的 checkout 支持查看最近请求日志和指标。离线规则与 HolmesGPT 模式分别标记。

独立模拟界面位于 `/?demo=1`。所有数据仅存在浏览器内存，刷新会恢复样例。演示账号为 `admin` 或 `operator`，密码均为 `demo`。可在 frontend 目录运行 `python -m http.server 17643 --bind 127.0.0.1 --directory public`，打开 http://127.0.0.1:17643/?demo=1。前端使用原生 ES 模块，无生产运行时依赖。

执行 `npm ci`、`npx playwright install chromium`、`npm test` 可重放模拟界面测试。可用 `CAUSELUME_BROWSER` 指定 Chromium 可执行文件。对使用临时数据库的 Java 实例，设置 `CAUSELUME_URL` 和 `CAUSELUME_ADMIN_PASSWORD` 后运行 `node tests/live-console.mjs`，会经浏览器创建、派发并关闭一张验收工单。

列表使用服务器分页。工单可按标题或编号、状态、服务、处理人查询，告警支持服务和级别筛选，调查列表仅加载摘要。待执行和运行中任务每 5 秒更新，页面隐藏或对话框打开时暂停发起新查询，离开调查页即停止轮询。完整证据按任务读取。证据快照、推断、不确定性及人工反馈分区展示，历史结果仅显示已保存字段。

三档宽度的可复现截图验收在 `tests/layout.spec.js`。设置 `CAUSELUME_CAPTURE_DEMO=1` 并运行 `npx playwright test layout.spec.js`，会从内存演示入口写入 `docs/images/`，只使用合成样本。
