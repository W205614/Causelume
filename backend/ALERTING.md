# 告警接入与通知

Alertmanager receiver 向 `/api/integrations/alertmanager/{integrationId}` 发送 POST 请求，启用 `send_resolved: true`，通过 `http_config.authorization.credentials_file` 提供该接入的独立 Bearer 凭据。平台管理员通过 `ALERTMANAGER_REGISTRY_FILE` 绑定接入 ID、团队 `slug` 和令牌。旧的 `ALERTMANAGER_TOKEN` 与 `ALERTMANAGER_INTEGRATION_ID`（默认 `primary`）继续绑定 `default` 团队。未知接入或凭据无效返回 401。此入口独立于浏览器会话。[生产配置示例](../docs/production-setup.md) · [Alertmanager webhook 配置](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)

每次接受 1–100 条标准 webhook 告警，请求体上限 256 KiB。新接入的 `labels.team` 必须等于绑定团队的 `slug`。旧接入可省略 `team`，如提供则须为 `default`。首次收到的告警在该团队内用 `labels.service` 匹配服务标签，服务未配置标签时匹配其名称。提供 `labels.environment` 时同时匹配环境。团队标签不符、没有匹配或匹配多个服务分别拒收为 403、404、409。标题取 `annotations.summary` 或 `labels.alertname`，等级支持 critical、fatal、high、warning、warn、info、none。

分组或投递顺序不参与去重。部分接收的批次返回 `alertIds`、逐条 `rejected`（`index` 从 0 开始、`status`、`reason`）及上游 `truncatedAlerts`。全部拒收时返回首个错误的 HTTP 状态。数据库异常回滚整批，允许上游按协议重试。通过 `causelume_alertmanager_dropped_total{reason="rejected"}` 和 `{reason="truncated"}` 区分拒收与上游截断。

- 集成、fingerprint 和 UTC startsAt 组成一次生命周期的身份，时间精度为微秒。首次 firing 自动创建一张工单。服务有有效默认负责人时直接派工，否则等待派工。重复投递返回最初的告警 ID。
- resolved 沿已保存的生命周期更新状态并追加工单记录，服务后来调整标签或停用也能处理。相同周期的迟到 firing 不会重新建单。先收到 resolved 时仅保存已恢复的告警。相同 fingerprint 再次发生故障时使用新的 startsAt。
- 本队管理员或平台管理员 POST `/api/services/{id}/maintenance`，提供未来的 `until` 和 `reason`。有效期内继续收集告警、工单和处理记录，抑制常规工单通知。到期后下一个业务事件正常通知。可 POST `/api/services/{id}/maintenance/end` 提前结束。
- firing、resolved、人工派工和关闭与通知记录在同一数据库事务提交。收件人依次回退到有效负责人、启用的本队管理员、启用的平台管理员。用户通过 `GET /api/notifications` 查看最近 50 条仍有权读取的通知，使用 `nextCursor` 作为 `afterId` 读取更早记录。POST `/api/notifications/{id}/read` 标为已读。
- 邮件使用 Spring Boot `spring.mail.*` 设置及 `APP_SMTP_FROM`。投递仅使用事件发生时已保存、投递时仍有效的启用账号邮箱。账号停用、邮箱变化或收件域不再允许时，投递标记 FAILED 并保留站内通知。邮件只含事件类型、工单编号和需登录的查看链接。设置 APP_SMTP_RECIPIENT_DOMAINS 可指定逗号分隔的收件域，留空则使用 APP_SMTP_FROM 的域。没有地址的记录标记 NO_ADDRESS。有地址但未配置 SMTP 时保留 PENDING，配置并重启后继续处理。维护窗口开始前已经排队、尚未领取的邮件在窗口结束后继续投递。部署应为连接、读取、写入设置有限超时。[Spring Boot 邮件配置](https://docs.spring.io/spring-boot/reference/io/email.html)
- 撤销协作邀请后，对应通知失去可见性，待发邮件标记 `CANCELLED`，原因为 `ACCESS_REVOKED`。发送前也会检查收件权限。
- 首投失败且确定尚未接收时，连接拒绝或明确的 SMTP 4xx 最多额外尝试三次，延迟 10、60、300 秒。明确 5xx 或认证拒绝直接标记 FAILED。无法确定 DATA 是否已接收或进程在投递中重启时标记 UNKNOWN。
- 平台管理员通过 `GET /api/notifications?scope=delivery` 查看 FAILED、UNKNOWN 投递。核查接收情况后可 POST `/api/notifications/{id}/resend` 并提供 `reason`，系统记录操作者和原因。再次投递可能产生重复邮件。

验证命令为 `mvn -f backend/pom.xml -Dtest=AlertDeliveryTest,MailDeliveryTest,DeliveryPermissionsTest test`。SMTP 验收连接测试进程中的回环 SMTP 接收器。
