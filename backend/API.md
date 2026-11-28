# Java HTTP 接口

Web 页面和 `/api` 同源。带请求体的 POST 使用 `application/json`。登录成功后由容器写入 HttpOnly、SameSite=Strict 的会话 Cookie，空闲 30 分钟失效。

接口通常直接返回 JSON 对象或数组。业务错误包含 `message`。400 表示输入无效，401 需登录或会话失效，403 表示当前权限不允许该动作，404 表示记录不存在或不可见，409 表示重复、状态冲突或需刷新，413 表示请求体或导出过大，415 表示媒体类型不符，422 表示取证配置或范围不满足要求，429 表示登录或调查容量已达上限。

## 团队范围与成员管理

每个服务只属于一个团队，告警、工单和调查沿服务关系继承归属。同名服务可分属不同团队。平台管理员（账号角色 `ADMIN`）拥有全局范围，管理团队、账号、告警接入、数据源绑定和审计。团队管理员（成员角色 `MANAGER`）可管理本队普通成员、服务设置和派工。普通成员（`MEMBER`）可以访问所属团队的业务，按工单角色执行相应操作。一个账号可以加入多个团队。V9 升级时已有服务和账号归入 `default` 团队并保留记录编号。

`GET /api/users`、`/api/services`、`/api/alerts`、`/api/tickets`、`/api/tasks`、三类 `/page` 列表、`/api/improvements/page` 和 `/api/overview` 支持正整数 `teamId`。省略时使用调用者的全部授权范围，指定时与授权范围取交集。越权团队筛选返回空列表或相应的零计数。邀请权限仅适用于指定工单。成员关系按请求读取，写操作在事务内复核。

| 方法和路径 | 契约 |
|---|---|
| `GET /api/teams` | 返回可见团队的 `id`、`slug`、`name`、`teamRole`。平台管理员查询未加入的团队时 `teamRole` 为 `null` |
| `POST /api/teams` | 平台管理员传 `name`、`slug`。`slug` 为 1–64 个小写 ASCII 字母、数字、`-` 或 `_`，首字符须为字母或数字。重复标识返回 409 |
| `GET /api/teams/{id}/members` | 平台管理员或本队管理员读取 `id`、`username`、`active`、`teamRole` |
| `POST /api/teams/{id}/members` | 以 `username` 和 `role` 加入已启用的账号或更新角色。团队管理员只管理 `MEMBER`，平台管理员可设置或变更 `MANAGER`。成功返回 `{"ok":true}` |
| `POST /api/teams/{id}/members/{account}/remove` | `{account}` 为账号编号，提交 `{}` 或 `{"successorId":42}`。权限与成员角色管理一致。接手人必须是本队另一位启用成员。成功返回 `{"ok":true}` |
| `POST /api/users/{id}/status` | 平台管理员以 `{"active":false}` 停用账号并使其现有会话失效 |

离队时交接该团队的服务、未关闭工单和未完成改进项，工单保留状态。缺少接手人时清空负责人，工单进入待分派，改进项在原工单中待交接。停用账号对其所属团队执行无接手人交接，并撤销收到的协作邀请。账号离开最后一个团队也会撤销其收到的邀请。成员变更和交接有审计记录。

| 方法和路径 | 输入与结果 |
|---|---|
| GET /api/health | 查询数据库，正常返回 `{"status":"UP"}` |
| POST /api/login | username、password → id、username、role |
| GET /api/me；POST /api/logout | 当前用户；注销并失效会话 |
| GET/POST /api/users | 查看授权范围内的派工名单。平台管理员传 `username`、`password`、`role` 创建用户，可选 `email`、`teamId`。省略 `teamId` 时加入默认团队，以 `MEMBER` 建立成员关系 |
| GET/POST /api/services | 读取授权目录。平台管理员或目标团队管理员传 `name`、`description`、`resource`、可选 `teamId` 登记服务。省略 `teamId` 时使用默认团队。`resource` 填公开的资源标识 |
| GET/POST /api/alerts | 倒序列表。本队成员可手动提交 `serviceId`、`source`、`externalId`、`severity`、`title`、`occurredAt`（含时区）。机器 webhook 见[告警与通知](ALERTING.md) |
| GET/POST /api/tickets | 倒序工单。提交 `serviceId`、`title`、`requestId`（UUID）、可选 `alertId` 和 `priority` 创建本队工单并返回详情 |
| GET /api/tickets/{id} | 主责方可读工单和按时间顺序排列的 `timeline`。受邀账号得到协作详情 |
| POST /api/tickets/{id}/actions | action、version、note，ASSIGN 还需 assigneeId，返回更新后的详情 |
| POST /api/tickets/{id}/export?format=json 或 markdown | 平台管理员或工单负责人以 JSON 请求体 `{}` 导出复盘并记录审计。默认 JSON，`markdown` 返回下载文本 |
| GET /api/audit | 平台管理员查看关键业务操作，含 `actor`、`action`、`targetId`、`createdAt` |

- 告警等级取 INFO、WARNING、CRITICAL。对同一服务、来源、外部事件 ID 的重投保留首条告警，包括服务后来停用的情况；停用服务仍不允许接收新事件。
- 每条工单同时表示待调查事件和它的协作记录。一个告警至多关联一个工单。同一操作者的 requestId 重投返回已有工单，包括其服务后来停用的情况；请求内容冲突返回 409。停用服务仍不允许创建新工单。
- `OPEN` → `ASSIGNED` → `PROCESSING` → `VERIFIED` → `CLOSED` 依次使用 `ASSIGN`、`START`、通过的 `VERIFY` 和 `CLOSE`。本队成员可在未关闭时用 `NOTE` 追加观察，状态推进由工单负责人、本队管理员或平台管理员执行。`VERIFY` 的 `note` 记录人工恢复验证，`CLOSE` 的 `note` 存为复盘。
- `ASSIGN` 用于 `OPEN` 和 `ASSIGNED`，`REASSIGN` 用于 `PROCESSING` 和 `VERIFIED`。两者只允许本队管理员或平台管理员执行，目标为本队启用成员。转派需要当前 `version`、新 `assigneeId` 和交接说明 `note`，保留当前状态并通知新负责人。
- 每次操作同时比较并递增 version，状态、审计和时间线在同一事务提交。并发写入返回 409 后需重新读取并审阅新记录。
- 首次创建文件数据库时，通过环境注入 APP_ADMIN_PASSWORD 和 APP_OPERATOR_PASSWORD，分别初始化 admin 和 operator。新增密码至少 12 个字符、最多 72 个 UTF-8 字节，以 BCrypt 哈希存储。重启沿用已有账户。
- APP_DB_URL 默认 `jdbc:h2:file:./data/causelume`。文件库适用于单实例部署，Flyway 在启动时按版本迁移。

执行 `mvn -f backend/pom.xml test` 运行真实 HTTP、数据库迁移、用户权限和工单流转集成测试。

## 单工单协作

| 方法和路径 | 契约 |
|---|---|
| `GET /api/collaborations?afterId=…` | 返回当前账号的有效邀请，按 `inviteId` 倒序、每次最多 100 项。继续读取时用最后一项的 `inviteId` 作为 `afterId` |
| `GET /api/collaborations/{ticket}` | 受邀者读取工单编号、标题、状态、优先级、创建时间、最近 100 条协作留言及明确共享的调查摘要。共享调查正文以 `GET /api/tasks/{id}` 读取 |
| `POST /api/collaborations/{ticket}/notes` | 受邀者或主责方提交 `{"note":"…"}`，最多 2000 字符，工单已关闭时返回 409 |
| `GET /api/tickets/{ticket}/invites` | 工单负责人、本队管理员或平台管理员读取有效邀请的 `id`、`username`、`createdAt` |
| `POST /api/tickets/{ticket}/invites` | 当前工单负责人传 `username`、可选 `taskIds`。受邀者须为非平台管理员的已启用其他团队成员，且未加入主责团队。最多指定 100 个不同的本工单 `SUCCEEDED` 调查。省略 `taskIds` 时仅共享工单摘要和协作留言。成功返回邀请 `id`，已有有效邀请或工单已关闭时返回 409 |
| `POST /api/tickets/{ticket}/invites/{invite}/revoke` | 工单负责人、本队管理员或平台管理员撤销指定邀请。成功返回 `{"ok":true}`，不存在或已撤销时返回 404 |

受邀者只获得协作详情、明确共享的调查以及协作留言权限。发起调查、派工、关闭和导出仍受主责方的操作规则约束。已获工单访问权但无权执行的操作返回 403，未共享的调查和撤销后的协作直链返回 404。撤销会隐藏对应站内通知并取消待发邀请邮件，邮件发送前也会核对当前授权。

## 调查与服务证据

- `GET /api/tasks/preview?ticketId=…` 返回已分派或处理中的工单当前服务绑定、配置的模式、`serviceVersion` 和默认窗口。工单负责人或平台管理员可读取。Checkout 的 `recentSnapshot=true` 表示取证范围由示例服务近期保留的记录决定。
- `POST /api/tasks` 可携带预览的 `serviceVersion`。若服务配置已更新，返回 409 并要求重新确认。既有请求可继续省略该字段。
- 平台管理员或本队管理员可用 `GET /api/services/{id}/source-check` 检查服务的现有绑定。`available` 表示各查询能否成功完成，每项 `sampleStatus` 为 `present`、`empty` 或 `unavailable`。检查与调查使用同一服务标签、环境和团队范围。未绑定或缺少有效团队范围时返回 422。绑定字段和共享源的配置见[工单与复盘](INCIDENTS.md)和[生产接入手册](../docs/production-setup.md)。

| 方法和路径 | 契约 |
|---|---|
| GET /api/services/{id}/metrics 或 /logs | 按团队权限读取 Checkout 示例证据。服务停用时返回 409，未绑定 Checkout 示例源时返回 422，可改用调查结果查看证据 |
| POST /api/tasks | `ticketId`、`requestId`（UUID）。已分派或处理中的工单由负责人或平台管理员发起，创建时返回 `PENDING`。后台任务保存本次数据源、服务标签、环境、适用团队标识和窗口 |
| GET /api/tasks 或 /api/tasks?ticketId={id} | 读取授权范围内的近期任务摘要。完整结果使用 `GET /api/tasks/{id}` |
| POST /api/tasks/{id}/feedback | 已结束任务由平台管理员或工单负责人提交一次 `feedback`。冲突返回 409 |

同一工单只能有一个等待中或运行中的任务。请求标识归属发起账号，重投时返回原任务。每次调查经 PENDING → RUNNING → SUCCEEDED 或 FAILED；失败留存原因。单实例工作者在事务外调用 Python，重启后接续 PENDING、标记遗留 RUNNING 为 FAILED，避免重复调用模型。

可选的 windowStart、windowEnd 必须同时提供且含时区；省略时由服务端选择窗口。输入时间先四舍五入到微秒，再校验、存储和比较重投内容，与数据库原有精度转换一致；规范化后的窗口必须大于零且不超过一小时。同一 requestId 的窗口在微秒精度不同则返回 409。

Java 将服务器端 AGENT_TOKEN 作为 Bearer 凭据，向 AGENT_URL 的 /investigate 发送字符串 taskId、服务名称 service。连接限时 3 秒，单次响应限时 95 秒。成功返回必须匹配 taskId，含 mode(offline/holmes)、非空 evidence（每项包括 source、detail）、possibleCause、uncertainty、recommendation。Java 校验后存储。422、504、服务不可达及无效数据会留下失败记录。反馈及任务创建记录于操作审计。DEMO_URL 的 /logs、/metrics 使用独立 EVIDENCE_TOKEN，只读代理的响应限时 5 秒。

## 分页和概览

`GET /api/users`、`/api/services` 使用 `afterId` 和 `limit` 分页，`limit` 默认为 100，范围 1–100。存在下一页时 `X-Next-Cursor` 返回下次使用的 `afterId`，`Link` 头保留所选 `teamId`。

`GET /api/tickets/page`、`/api/alerts/page`、`/api/tasks/page` 的 `page` 从 1 开始，`size` 默认为 20、最大 100。响应为 `{items,total,page,size}`，默认按唯一编号倒序，超出末页返回空 `items`。总数和结果在同一事务读取。有新记录写入时，下次翻页可能看到变化。

| 列表 | 可选筛选 | 行内容 |
|---|---|---|
| tickets/page | teamId、serviceId、assigneeId、status、priority、q、scope | 工单与当前负责人、服务名。`q` 匹配标题、复盘、影响、原因、不确定性或完整编号（允许 `#` 前缀） |
| alerts/page | teamId、serviceId、severity | 告警、服务名及关联 `ticketId`，未建单时为 `null` |
| tasks/page | teamId、ticketId、serviceId、status | 状态、工单标题、服务名、起止时间、错误代码、最多 240 字的摘要。旧记录可能没有摘要 |

`GET /api/tasks/{id}` 向主责方返回调查分析、观测证据及反馈，向受邀者返回所共享结果的分析与证据。`GET /api/overview` 在同一个授权团队范围内统计 `openTickets`、`criticalAlerts`、`activeTasks`、`services`、`alertCounts`、`taskCounts`，并返回最近 5 条工单和告警。`activeTasks` 包含 `PENDING` 与 `RUNNING`。

- `GET /api/overview` 同时返回 `workItems`，包含 `mine`、`unassigned`、`recovery` 三组分页摘要。每组最多 5 条，按优先级、创建时间、编号排序。个人待办包含待关闭的已验证工单。待派工或交接包含尚未分派的工单及负责人已停用的未关闭工单。普通成员的恢复待办仅包含自己负责的工单，团队管理员还可查看本队待验证工单。用 `GET /api/tickets/page?scope=…` 查看完整分页。
- `GET /api/improvements/page` 返回当前账号在授权范围内负责的改进项，按截止日期排序，`status` 默认为 `OPEN`，可切换 `DONE`。待交接项在原工单详情中读取，由负责人、本队管理员或平台管理员通过 `POST /api/tickets/{id}/improvements/{item}/assign` 提交 `ownerId` 重派给本队启用成员。
- 平台管理员可用 `GET /api/recovery/status` 查看维护脚本报告的备份、隔离恢复和备份心跳状态。未配置时返回 `configured=false`。

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
