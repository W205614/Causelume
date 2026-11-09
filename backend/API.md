# Java HTTP 接口

Web 页面和 /api 同源。POST 使用 application/json，登录成功后由容器写入 HttpOnly、SameSite=Strict 的会话 Cookie，空闲 30 分钟失效。管理员可派工、创建用户和服务、查看审计。运维可查看派工名单、接入告警、建单、留言及处理自己负责的工单。

接口直接返回 JSON 对象或数组。业务错误返回 `{"message":"..."}`。400 表示字段无效，401 需登录，403 权限不足，404 记录不存在，409 表示重复或状态竞争需要刷新，413 表示请求体过大，415 表示媒体类型不符，429 表示登录或调查容量已达上限。

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

- 告警等级取 INFO、WARNING、CRITICAL。对同一服务、来源、外部事件 ID 的重投保留首条告警，包括服务后来停用的情况；停用服务仍不允许接收新事件。
- 每条工单同时表示待调查事件和它的协作记录。一个告警至多关联一个工单。同一操作者的 requestId 重投返回已有工单，包括其服务后来停用的情况；请求内容冲突返回 409。停用服务仍不允许创建新工单。
- OPEN → ASSIGNED → PROCESSING → VERIFIED → CLOSED 依次使用 ASSIGN、START、VERIFY、CLOSE。管理员可在 ASSIGNED 状态改派。VERIFY 的 note 记录人工恢复验证，CLOSE 的 note 存为复盘。未关闭时可用 NOTE 追加观察。
- 每次操作同时比较并递增 version，状态、审计和时间线在同一事务提交。并发写入返回 409 后需重新读取并审阅新记录。
- 首次创建文件数据库时，通过环境注入 APP_ADMIN_PASSWORD 和 APP_OPERATOR_PASSWORD，分别初始化 admin 和 operator。新增密码至少 12 个字符、最多 72 个 UTF-8 字节，以 BCrypt 哈希存储。重启沿用已有账户。
- APP_DB_URL 默认 `jdbc:h2:file:./data/causelume`。文件库适用于单实例部署，Flyway 在启动时按版本迁移。

执行 `mvn -f backend/pom.xml test` 运行真实 HTTP、数据库迁移、用户权限和工单流转集成测试。

## 调查与服务证据

| 方法和路径 | 契约 |
|---|---|
| GET /api/services/{id}/metrics 或 /logs | 登录用户读取服务实测证据。当前只接入 checkout，其他服务返回 422 |
| POST /api/tasks | ticketId、requestId(UUID)。已分派或处理中，由负责人或管理员发起，创建时返回 PENDING |
| GET /api/tasks；GET /api/tasks?ticketId={id} | 登录用户查看最近任务摘要；完整结果从 GET /api/tasks/{id} 获取 |
| POST /api/tasks/{id}/feedback | 已结束任务由管理员或负责人提交一次 feedback；冲突返回 409 |

同一工单只能有一个等待中或运行中的任务。请求标识归属发起账号，重投时返回原任务。每次调查经 PENDING → RUNNING → SUCCEEDED 或 FAILED；失败留存原因。单实例工作者在事务外调用 Python，重启后接续 PENDING、标记遗留 RUNNING 为 FAILED，避免重复调用模型。

可选的 windowStart、windowEnd 必须同时提供且含时区；省略时由服务端选择窗口。输入时间先四舍五入到微秒，再校验、存储和比较重投内容，与数据库原有精度转换一致；规范化后的窗口必须大于零且不超过一小时。同一 requestId 的窗口在微秒精度不同则返回 409。

Java 将服务器端 AGENT_TOKEN 作为 Bearer 凭据，向 AGENT_URL 的 /investigate 发送字符串 taskId、服务名称 service。连接限时 3 秒，单次响应限时 95 秒。成功返回必须匹配 taskId，含 mode(offline/holmes)、非空 evidence（每项包括 source、detail）、possibleCause、uncertainty、recommendation。Java 校验后存储。422、504、服务不可达及无效数据会留下失败记录。反馈及任务创建记录于操作审计。DEMO_URL 的 /logs、/metrics 使用独立 EVIDENCE_TOKEN，只读代理的响应限时 5 秒。

## 分页和概览

GET /api/tickets/page、/api/alerts/page、/api/tasks/page 共用从 1 开始的 page 和默认 20、最大 100 的 size。响应为 `{items,total,page,size}`，按唯一编号倒序排列，超出最后一页返回空 items。每次查询的总数和结果在同一事务读取；有新记录写入时，下一次翻页可能看到变化。

| 列表 | 可选筛选 | 行内容 |
|---|---|---|
| tickets/page | serviceId、assigneeId、status、q | 工单与当前负责人、服务名。q 匹配标题字面子串或完整编号（允许 # 前缀） |
| alerts/page | serviceId、severity | 告警、服务名及关联 ticketId，未建单时为 null |
| tasks/page | ticketId、serviceId、status | 状态、工单标题、服务名、起止时间、错误代码、最多 240 字的摘要。旧记录可能没有摘要 |

GET /api/tasks/{id} 返回单条调查完整分析、观察证据及反馈。GET /api/overview 从全库统计 openTickets、criticalAlerts、activeTasks、services、alertCounts、taskCounts，并附最近 5 条工单和告警。activeTasks 包含 PENDING 与 RUNNING。

旧数组入口 /api/tickets、/api/alerts、/api/tasks 最多返回最近 100 项。任务数组返回摘要；取证据的调用方应改用详情接口，浏览全部历史请用分页接口。

会话、可信 Origin、登录审计、上游限额、队列容量和目录游标见 [安全与并发约束](SECURITY.md)。

## 平台运行指标

独立管理端口的 `GET /actuator/prometheus` 使用 `Authorization: Bearer <MANAGEMENT_TOKEN>`。每次抓取执行六个只读聚合查询。业务指标仅使用固定状态标签。

| Prometheus 指标 | 含义 |
|---|---|
| `causelume_investigations{state="PENDING"}`、`{state="RUNNING"}` | 当前待执行和执行中的调查数量 |
| `causelume_investigation_queue_oldest_seconds` | 队列中最早创建的调查已等待的秒数，队列空时为 0 |
| `causelume_investigations_failed_recent` | 最近 15 分钟内结束且状态为 FAILED 的调查数 |
| `causelume_mail_delivery_attention{state="FAILED"}`、`{state="UNKNOWN"}` | 已确认投递失败和投递结果未知的通知数，可在通知的投递异常列表核对 |

Prometheus 装载 `deployment/observability/service-alerts.yml`，监控抓取中断、HTTP 5xx、调查排队、近期调查失败、异常邮件、数据库连接池压力，以及应用工作目录所在文件系统的剩余空间。要让平台自身告警自动建单，请先登记与抓取标签一致的服务名 `causelume` 并配置负责人。

宿主机其他挂载点、PostgreSQL 数据卷和备份仓库的容量需要分别采集。备份脚本在归档与仓库校验完成后请求 `BACKUP_HEARTBEAT_URL`；按 12 小时备份周期在独立服务配置心跳过期通知，便可覆盖主机停机和漏跑。未配置机外心跳时，只能从备份记录核查最近成功时间。
