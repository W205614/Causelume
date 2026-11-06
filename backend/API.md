# Java HTTP 接口

Web 页面和 /api 同源。POST 使用 application/json，登录成功后由容器写入 HttpOnly、SameSite=Strict 的会话 Cookie，空闲 30 分钟失效。管理员可派工、创建用户和服务、查看审计。运维可查看派工名单、接入告警、建单、留言及处理自己负责的工单。

接口直接返回 JSON 对象或数组。业务错误返回 `{"message":"..."}`。400 表示字段无效，401 需登录，403 权限不足，404 记录不存在，409 表示重复或状态竞争需要刷新，415 表示媒体类型不符。

| 方法和路径 | 输入与结果 |
|---|---|
| GET /api/health | 查询数据库，正常返回 `{"status":"UP"}` |
| POST /api/login | username、password → id、username、role |
| GET /api/me；POST /api/logout | 当前用户；注销并失效会话 |
| GET/POST /api/users | 查看不含密码的派工名单；管理员传 username、password、role 创建用户 |
| GET/POST /api/services | 服务目录；管理员传 name、description、resource 登记服务。resource 填公开的资源标识 |
| GET/POST /api/alerts | 倒序列表；serviceId、source、externalId、severity、title、occurredAt（含时区）|
| GET/POST /api/tickets | 倒序工单；serviceId、title、requestId（UUID）、可选 alertId，返回工单详情 |
| GET /api/tickets/{id} | 工单与按时间顺序排列的 timeline |
| POST /api/tickets/{id}/actions | action、version、note，ASSIGN 还需 assigneeId，返回更新后的详情 |
| GET /api/audit | 管理员查看关键业务操作，含 actor、action、targetId、createdAt |

- 告警等级取 INFO、WARNING、CRITICAL。对同一服务、来源、外部事件 ID 的重投保留首条告警。
- 每条工单同时表示待调查事件和它的协作记录。一个告警至多关联一个工单。同一操作者的 requestId 重投返回已有工单，请求内容冲突返回 409。
- OPEN → ASSIGNED → PROCESSING → VERIFIED → CLOSED 依次使用 ASSIGN、START、VERIFY、CLOSE。管理员可在 ASSIGNED 状态改派。VERIFY 的 note 记录人工恢复验证，CLOSE 的 note 存为复盘。未关闭时可用 NOTE 追加观察。
- 每次操作同时比较并递增 version，状态、审计和时间线在同一事务提交。并发写入返回 409 后需重新读取并审阅新记录。
- 首次创建文件数据库时，通过环境注入 APP_ADMIN_PASSWORD 和 APP_OPERATOR_PASSWORD，分别初始化 admin 和 operator。新增密码至少 12 个字符、最多 72 个 UTF-8 字节，以 BCrypt 哈希存储。重启沿用已有账户。
- APP_DB_URL 默认 `jdbc:h2:file:./data/causelume`。文件库适用于单实例部署，Flyway 在启动时按版本迁移。

执行 `mvn -f backend/pom.xml test` 运行真实 HTTP、数据库迁移、用户权限和工单流转集成测试。
