# 服务配置、复盘与历史

管理员 POST `/api/services/{id}` 提交名称、description、resource、environment、ownerId、runbook、active、sourceId、serviceLabel、version。修改使用乐观锁。停用后保留已有工单，停止创建新工单和接入新故障。已有机器告警仍能录入恢复信号。

数据源 ID 从管理接口 `GET /api/sources` 获取，清空 ID 表示解除绑定。服务关联数据源时，仅允许选择内部 Agent 注册的 ID。可 GET `/api/services/{id}/source-check` 检查该服务标签和环境的连接。内部地址和凭据在服务器侧配置。

创建工单可传 priority（P1–P4，默认 P3）。工单动作继续要求 version 和人工 note。管理员或工单负责人可使用如下动作。

| 动作 | 输入和效果 |
|---|---|
| PRIORITY | priority，调整未关闭工单优先级 |
| VERIFY | verification 为 PASSED、FAILED 或 UNKNOWN；仅 PASSED 转入 VERIFIED。旧客户端未传此字段时，显式 VERIFY 动作按 PASSED 记录 |
| CLOSE | VERIFIED 方可关闭。可附 postmortem，包含 impact、startedAt、endedAt、cause、uncertainty。兼容旧客户端单段 note 复盘 |
| REOPEN | 关闭后重新处理。负责人有效则进入 PROCESSING，否则重新进入 OPEN 等待派工 |

POST `/api/tickets/{id}/improvements` 填写 title、ownerId、dueDate。工单负责人、管理员或改进项负责人可 POST `/api/tickets/{id}/improvements/{itemId}` 更新 status（OPEN/DONE）。写入改进项会同步更新工单版本、时间线和操作审计。

详情展示最新 50 条时间线记录，按发生顺序排列。字段 timelineTotal 记录总数。GET `/api/tickets/{id}/timeline?page=1&size=20` 按最新记录优先分页，每页最多 100 条。工单分页查询增加 priority 筛选，q 可匹配标题、复盘、影响、原因、不确定性或完整编号。

管理员或工单负责人可 GET `/api/tickets/{id}/export?format=json` 或 format=markdown。导出工单、结构化复盘、处理记录、改进项及调查元数据，每类记录最多 1000 条。超出返回 413。完整调查分析与采样证据保留在调查详情。导出写入审计。管理员可 GET `/api/retention/preview?days=90` 预览截止日前的审计、通知、已恢复告警、最后一次关闭早于截止时间的已关闭工单及已结束调查数。此接口只有读取能力。

验证命令为 `mvn -f backend/pom.xml -Dtest=IncidentReviewTest,ServiceSourceTest,BrowseTest,OperationsTest test`。
