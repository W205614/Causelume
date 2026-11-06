# Linux 个人版接入手册

本手册供已验证候选版本接入外部资源使用。主机、域名、SMTP、异地仓库和外部探测均由操作者准备。配置和自动化入口已提供，接入时通过实测填写验收记录。

## 首次主机准备

1. 安装维护中的 Linux、Docker Engine 和 Compose v2，启用主机时间同步。工作目录设为 /opt/causelume。
2. 防火墙只向外开放 SSH（限制来源）及 80/443。PostgreSQL、Agent、Holmes、Prometheus、Loki、Alertmanager 与管理指标端口通过内部网络访问。
3. 拉取审核过的提交，复制 production.env.example 为 production.env。运行 python scripts/init_production.py，设置文件仅所属用户可读；Linux 上核对目录 0700、文件 0600。不要把目录交给公开静态站点。
4. 从发布工作流的 release-manifest 填写 APP_IMAGE、AGENT_IMAGE 和 APP_RELEASE。镜像摘要必须对应已通过检查的候选提交。
5. 编辑外部凭据及域名，先运行 Compose config --quiet，再使用 sh scripts/release.sh。部署依赖的角色、数据库和内部网络由配置创建。

~~~sh
docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml config --quiet
sh scripts/release.sh
~~~

平台与被监控项目共用主机时，按真实采集量测量内存、CPU、IO及磁盘增长。配置中的资源上限是保护边界，容量承诺须由实测给出。示例服务提供可复现故障，可在接入真实项目后按需要停用。

## 域名和 HTTPS

APP_DOMAIN 填域名，不带 https://；非标准端口需显式设置相应入口。ACME_EMAIL 填证书维护邮箱。DNS A/AAAA 必须指向可达主机，80/443 应从外部到达 Caddy。APP_PUBLIC_ORIGIN 由编排生成，生产会话强制 Secure 与 MFA。

首次使用环境中的 APP_ADMIN_PASSWORD/APP_OPERATOR_PASSWORD 初始化账户。进入账户安全绑定验证器、保存单次恢复码，再设置通知邮箱。已有数据库不会通过改变初始密码重置账户。应先轮换本次项目历史中曾通过聊天发送过的模型密钥。

真实验收包括证书链、域名、HTTP跳转、MFA/恢复码、退出登录、停用会话和机外访问。localhost 自签证书演练不能替代公网证书验收。

## SMTP 与通知

production.env 配置 SMTP_HOST、SMTP_PORT、SMTP_USERNAME、SMTP_FROM，secrets/SMTP_PASSWORD 填服务商授权凭据。默认启用 STARTTLS，connect/read/write 各5秒超时。收件邮箱在账户安全页维护。

投递过程有站内记录。临时拒绝按10秒、1分钟、5分钟最多重试三次；明确永久拒绝终止，发送结果不明不自动重发。管理员在通知中心的「邮件投递管理」查看失败与未知记录。人工重发可能产生重复，需填写依据。

验证实际收件、垃圾邮件与服务商限额。测试中的 Mailpit 只证明本地SMTP协议与业务记录；真实送达待服务商配置后验证。

## 真实应用与观测

部署配置位于 deployment/observability。为应用增加 Prometheus/Micrometer 入口和日志采集路径，统一 service/environment 标签。Prom 抓取配置、Alloy 文件路径及标签可以用配置接入。注册数据源仅由服务端维护，服务目录只选择批准的 sourceId。

生产 Agent 通过查询网关访问数据源；该网络没有 Loki 写入端点直连。网关仅允许固定GET查询与健康路径，实际查询仍受标签、时段、记录数和响应上限约束。数据库及模型密钥不进入浏览器。

服务编辑绑定环境、sourceId、serviceLabel 与负责人，先做连接检测。随后触发一次受控实际故障，验证告警、邮件、调查和人工恢复。告警恢复不会自行关闭工单。没有样本或来源不可达时应保留失败/不完整证据。

## 模型和预算

默认 AGENT_MODE=offline。准备有效凭据后，将 OPENAI_API_KEY 写入专用secret，AGENT_MODE=holmes，COMPOSE_PROFILES=holmes，并填写 MODEL_DAILY_BUDGET_USD 与 MODEL_RESERVATION_USD。更新配置重建相应容器后，从工单人工发起。

Java在事务中预留额度，明确授权的任务才可进入模型请求。无预留旧请求即使遇到模式切换也不能自动付费。上下文限制16384、输出1200、最多4步；费用预留是平台准入策略，供应商实际计费需核对。未知费用保持冻结，管理员填写账单依据后结算。外部模型调用需要另行授权后验证。

## 初始化和启用异地备份

配置 RESTIC_REPOSITORY、RESTIC_PASSWORD 和存储服务凭据。仓库应位于独立于该主机的位置，凭据权限只覆盖专属备份前缀。仓库加密密码和恢复操作说明另存离线副本，避免主机丢失后无法解密。

首次初始化专属仓库，已有仓库跳过 init：

~~~sh
docker compose --env-file production.env -f compose.production.yaml --profile maintenance build backup
docker compose --env-file production.env -f compose.production.yaml --profile maintenance run --rm --entrypoint restic backup init
docker compose --env-file production.env -f compose.production.yaml --profile maintenance run --rm backup
~~~

备份包含一致 pg_dump、摘要、发布提交和配置/秘密文件，加密后上传。配置内容敏感，即便是加密归档也需限制访问。工作卷使用固定暂存文件；restic历史快照不自动prune。保留/清理先预览并按所有者确认的策略执行。

安装 deployment/systemd 中 backup.service、backup.timer，启用后每12小时触发。BACKUP_HEARTBEAT_URL 指向独立的备份检查，只有备份与仓库校验成功后才发送心跳。

## 隔离恢复和回退

~~~sh
docker compose --env-file production.env -f compose.production.yaml --profile restore build restore-check
docker compose --env-file production.env -f compose.production.yaml --profile restore run --rm restore-check
docker compose --env-file production.env -f compose.production.yaml --profile restore stop restore-postgres
~~~

恢复任务只连接独立 restore 网络的临时PostgreSQL。它先校验加密仓库和归档摘要，恢复到独立库，使用迁移角色建表并验证应用角色可读取核心业务表计数。scratch数据库与恢复目录使用临时内存存储，较大备份应提前调整容量。脚本不会替换在线数据库。

首次和每月使用同一流程演练，并检查关键工单、证据和反馈。主机灾难恢复时，从备份恢复配置和secret到受限路径，初始化独立角色，恢复匹配版本数据库，通过应用登录/只读/API验证后再切换入口。RPO24小时、RTO2小时须以真实异地仓库和新主机的计时证明，不能用本地临时仓库的结果替代。

数据库已应用新迁移后，旧镜像是否可用取决于兼容性。回退需采用已验证的兼容版本或一整套恢复集，保留原失败现场。

## 机外探测

复制 deployment/systemd/heartbeat.env.example 为 /opt/causelume/heartbeat.env，填真实 PUBLIC_HEALTH_URL、EXTERNAL_HEARTBEAT_URL，权限0600。安装 heartbeat.service/timer，按服务商要求设置周期和宽限。探测脚本先检查公网TLS/数据库健康，再发送心跳。

分别停止平台和备份心跳，确认机外服务发送通知。探测服务必须位于该云主机之外；同机告警无法覆盖整机停电或网络中断。

## 发布与升级

每次升级先看 release-manifest、安全扫描与数据迁移结果。release.sh 会检查配置和磁盘、拉取固定镜像，已运行实例先备份，再启动迁移及健康校验。维护中安排停止业务写入，检查未知调查与通知。发布后核对历史数据和MFA，记录实际使用提交和镜像摘要。定期扫描依赖与镜像，补丁更新重跑直接影响的恢复与业务验收。
