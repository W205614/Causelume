# 运维控制台

阶段一所有页面使用浏览器内存中的模拟记录。刷新页面会恢复初始样例。管理员账号为 `admin`，运维账号为 `operator`，两者密码均为 `demo`。页面顶部标记数据来源，调查结果使用合成日志。

在 frontend 目录运行 `python -m http.server 17643 --bind 127.0.0.1 --directory public`，浏览器打开 http://127.0.0.1:17643/。界面使用标准 ES 模块与浏览器表单、对话框，无前端运行时依赖。

本地浏览器回归：`npm ci`、`npx playwright install chromium`、`npm test`。也可设置 `CAUSELUME_BROWSER` 为兼容的 Chromium 浏览器可执行文件。测试覆盖模拟账号、页面导航、无权限状态、搜索和完整模拟工单处理。
