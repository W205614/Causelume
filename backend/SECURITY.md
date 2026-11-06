# 单机安全与故障处理

## 登录和部署入口

- 本地 HTTP 模式下 APP_PUBLIC_ORIGIN 留空，Compose 只发布到回环。登录后写入 HttpOnly、SameSite=Strict 会话 Cookie，空闲 30 分钟失效。服务端在登录时更换会话，登出时销毁会话。
- 反向代理终止 TLS 时，设置 APP_PUBLIC_ORIGIN 为浏览器实际使用的 HTTPS origin（包括非标准端口，不含路径或尾斜线）。应用用该固定值校验浏览器写入的 Origin，并始终设置 Secure Cookie。将 Java 端口限制在代理可达范围，代理负责限制请求速率及 TLS 配置。
- Java 使用直接 TCP 对端作为限流来源，忽略 Forwarded、X-Forwarded-For、X-Forwarded-Host、X-Forwarded-Proto。经过同一代理的用户共享该对端额度。本机 HTTP 写入以请求的本地协议和 Host 比较 Origin。脚本可不发送 Origin，所有写操作仍要求 application/json 和相应的会话权限。
- 每 60 秒固定窗口内，每个连接来源最多 10 次登录尝试，整个单实例最多 200 次。超限返回 429，可以在一分钟后手动重试。重启重新计算窗口。按来源限制可以避免恶意输错密码锁死他人的账号。未知用户和密码错误返回相同 401，均使用 BCrypt 校验。登录成功和登出写审计，登录失败只记录来源，不记录提供的秘密。被限流的重复请求在一个窗口内合并审计，避免放大写入。
- 修改密码和开始 MFA 绑定共用独立的当前密码校验窗口。每账号每分钟最多 10 次请求，单实例合计最多 200 次，跨会话累计。失败审计为 `REAUTH_DENIED`，限流首次写入 `REAUTH_LIMITED` 并返回 429。账号管理写操作在事务内再次检查操作者的会话版本。
- 请求体上限 16 KiB，基于实际读取字节数执行，包括分块传输，越界返回 413。业务字段仍按各自契约校验。

## 调查、配额和持久化

- 每个工单最多一个 PENDING 或 RUNNING 调查，整个单实例最多 32 个在途任务。新增请求在数据库事务内依次持有工单行锁和全局准入行锁，检查容量后插入。相同账号的相同 requestId 返回原任务，当前工单在途冲突返回 409，队列满返回 429。外部调用期间不占用业务事务。
- Java 对调查和只读证据代理的上游响应均限制 128 KiB，按块累计，超限在 JSON 解析前拒绝。连接超时 3 秒；总响应期限包括完整响应体，调查 95 秒、证据读取 5 秒。每项分析最多 2000 个 Unicode 码点，证据最多 10 条，来源和详情分别最多 100、2048 个码点。
- startedAt、finishedAt 由数据库计时。旧记录的未知时间为 null。工作者先持久化 RUNNING，再开始外部调用。终态写入必须同时满足当前状态为 RUNNING 且距开始不足 120 秒。同一进程后续周期会扫描超期作业并写入 DEADLINE_EXCEEDED。持久化失败时数据库中的状态仍代表最后一次成功写入；恢复后通过扫描收尾，不重发未知结果的模型调用。进程重启后，原 RUNNING 记为 RESTART_INTERRUPTED，PENDING 继续执行。
- errorCode 区分 UPSTREAM_TIMEOUT、UPSTREAM_ERROR、SERVICE_UNAVAILABLE、INTERRUPTED、WORKER_ERROR、DEADLINE_EXCEEDED、RESTART_INTERRUPTED。管理员结合任务 ID 和应用日志排查；超时或中断的模型请求按费用未知处理，再根据供应商账单核对。

## 目录和审计游标

GET /api/users、/api/services 按 ID 升序，/api/audit 按 ID 降序并限定管理员。默认返回最多 100 项的 JSON 数组。limit 接受 1–100。存在后续结果时，X-Next-Cursor 返回当前页最后一个 ID，Link 给出下一页路径。下一页带 afterId 与 limit；无下一页时两个响应头均省略。此契约保留原有数组结构。
