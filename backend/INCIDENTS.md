# 服务配置、复盘与历史

本队管理员或平台管理员 POST `/api/services/{id}`，提交 `name`、`description`、`resource`、`environment`、`active`、`version`，以及可选的 `ownerId` 和 `runbook`。负责人须为本队启用成员，`version` 冲突返回 409。停用后保留已有工单，停止创建新工单和接入新故障。已有机器告警仍能录入恢复信号。

平台管理员通过 `GET /api/sources` 读取 Agent 注册的数据源，使用 `sourceId`、`serviceLabel` 和 `environment` 设置绑定。平台管理员更新服务时须提交要保留的绑定，省略或清空 `sourceId` 会解除绑定。目录内同一数据源的团队范围配置变化后，以 `rebindSource=true` 重新绑定。团队管理员编辑服务时沿用现有绑定，包括已绑定的环境。内部地址和凭据在服务器侧配置。

本队管理员或平台管理员可 GET `/api/services/{id}/source-check`。共享数据源的团队范围由服务所属团队的 `slug` 决定，与服务标签和环境一起用于检测及调查。非默认团队只能绑定已启用 `teamScoped` 的数据源。默认团队可继续使用专用旧源和 Checkout。[观测接入配置](../docs/production-setup.md)

创建工单可传 `priority`（P1–P4，默认 P3）。工单动作继续要求 `version` 和人工 `note`。平台管理员、本队管理员或工单负责人可使用如下动作。

| 动作 | 输入和效果 |
|---|---|
| PRIORITY | priority，调整未关闭工单优先级 |
| VERIFY | verification 为 PASSED、FAILED 或 UNKNOWN；仅 PASSED 转入 VERIFIED。旧客户端未传此字段时，显式 VERIFY 动作按 PASSED 记录 |
| CLOSE | VERIFIED 方可关闭。可附 postmortem，包含 impact、startedAt、endedAt、cause、uncertainty。兼容旧客户端单段 note 复盘 |
| REOPEN | 关闭后重新处理。负责人有效则进入 PROCESSING，否则重新进入 OPEN 等待派工 |

工单负责人或平台管理员可 POST `/api/tickets/{id}/improvements`，填写 `title`、`ownerId`、`dueDate`。他们和改进项负责人可 POST `/api/tickets/{id}/improvements/{itemId}` 更新 `status`（`OPEN` 或 `DONE`），完成时可附 `evidence`。账号离队或停用后，无接手人的未完成改进项留在原工单，由工单负责人、本队管理员或平台管理员 POST `/api/tickets/{id}/improvements/{itemId}/assign`，提交接手人的 `ownerId`。接手人须为本队启用成员。改进项的变更同步更新工单版本、时间线和操作审计。

跨队协作由工单负责人按账号发出邀请，可选择共享哪些已成功完成的调查。受邀者通过「协作工单」阅读摘要和所选结果，并可在未关闭时留言。协作留言进入主责方时间线。工单负责人、本队管理员或平台管理员可以撤销邀请。邀请、共享和撤销的路径与权限见 [API 契约](API.md#单工单协作)。

详情展示最新 50 条时间线记录，按发生顺序排列。字段 timelineTotal 记录总数。GET `/api/tickets/{id}/timeline?page=1&size=20` 按最新记录优先分页，每页最多 100 条。工单分页查询增加 priority 筛选，q 可匹配标题、复盘、影响、原因、不确定性或完整编号。

平台管理员或工单负责人可 POST `/api/tickets/{id}/export?format=json` 或 `format=markdown`，请求体为 JSON 空对象。导出工单、结构化复盘、处理记录、改进项及调查元数据，每类记录最多 1000 条。超出返回 413。完整调查分析与采样证据保留在调查详情。导出写入审计。平台管理员可 GET `/api/retention/preview?days=90` 预览截止日前的审计、通知、已恢复告警、最后一次关闭早于截止时间的已关闭工单及已结束调查数。此接口只有读取能力。

验证命令为 `mvn -f backend/pom.xml -Dtest=IncidentReviewTest,ServiceSourceTest,BrowseTest,OperationsTest test`。
