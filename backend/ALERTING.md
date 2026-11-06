# 告警接入与通知

Alertmanager 的 webhook receiver 将 POST 请求发送到 `/api/integrations/alertmanager/primary`，启用 `send_resolved: true`。可用 `ALERTMANAGER_INTEGRATION_ID` 设置本实例的集成 ID。通过 receiver 的 `http_config.authorization.credentials_file` 提供 Bearer 凭据，与应用的 `ALERTMANAGER_TOKEN` 匹配。此入口独立于浏览器会话。未配置令牌时返回 401。[Alertmanager 官方 webhook 配置](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)

每次接受 1–100 条标准 webhook 告警。每条使用 `labels.service` 匹配已登记服务的名称，以 `annotations.summary` 或 `labels.alertname` 为标题。支持 critical、fatal、high、warning、warn、info、none 级别。分组或投递顺序不参与去重。

- 集成、fingerprint 和 UTC startsAt 组成一次生命周期的身份，时间精度为微秒。首次 firing 自动创建一张工单。服务有有效默认负责人时直接派工，否则等待派工。重复投递返回最初的告警 ID。
- resolved 更新生命周期并追加工单记录。相同周期的迟到 firing 不会重新建单。先收到 resolved 时仅保存已恢复的告警。相同 fingerprint 再次发生故障时使用新的 startsAt。
- 管理员 POST `/api/services/{id}/maintenance`，提供未来的 until 和 reason。有效期内继续收集告警、工单和处理记录，抑制通知。到期后下一个业务事件正常通知。可 POST `/api/services/{id}/maintenance/end` 提前结束。
- firing、resolved、人工派工和关闭与通知记录在同一个数据库事务提交。收件人是负责人，缺少有效负责人时发给启用的管理员。每位用户通过 `GET /api/notifications` 查看自己的最近 50 条，使用返回的 nextCursor 作为 afterId 加载更早记录。POST `/api/notifications/{id}/read` 标为已读。
- 邮件使用 Spring Boot `spring.mail.*` 设置及 `APP_SMTP_FROM`。收件邮箱取事件发生时账号的 email。没有地址的记录标记 NO_ADDRESS。有地址但未配置 SMTP 时保留 PENDING，配置并重启后继续处理。维护窗口开始前已经排队、尚未领取的邮件在窗口结束后继续投递。部署应为连接、读取、写入设置有限超时。[Spring Boot 邮件配置](https://docs.spring.io/spring-boot/reference/io/email.html)
- 首投失败且确定尚未接收时，连接拒绝或明确的 SMTP 4xx 最多额外尝试三次，延迟 10、60、300 秒。明确 5xx 或认证拒绝直接标记 FAILED。无法确定 DATA 是否已接收或进程在投递中重启时标记 UNKNOWN。
- 管理员通过 `GET /api/notifications?scope=delivery` 查看 FAILED、UNKNOWN 投递。核查接收情况后可 POST `/api/notifications/{id}/resend` 并提供 reason，系统记录操作者和原因。再次投递可能产生重复邮件。

验证命令为 `mvn -f backend/pom.xml -Dtest=AlertDeliveryTest,MailDeliveryTest,DeliveryPermissionsTest test`。SMTP 验收连接测试进程中的回环 SMTP 接收器。
