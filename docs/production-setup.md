# Linux 单实例小团队接入手册

本手册包含主机准备、外部服务配置、备份恢复和版本更新步骤。准备主机、域名、SMTP、异地仓库和外部探测后，按对应章节接入。部署采用共享运维工作区，成员共享查看服务、工单和诊断证据；部署前确认参与成员与数据可见范围。

## 首次主机准备

1. 安装维护中的 Linux、Docker Engine 和 Compose v2，启用主机时间同步。工作目录设为 /opt/causelume。
2. 防火墙只向外开放 SSH（限制来源）及 80/443。PostgreSQL、Agent、Holmes、Prometheus、Loki、Alertmanager 与管理指标端口通过内部网络访问。
3. 拉取审核过的提交，复制 production.env.example 为 production.env。以专用部署用户运行 `python scripts/init_production.py`。Linux 上 `secrets/` 权限为 0700，其他宿主用户无法遍历。内部凭据文件权限为 0444，Compose 按服务授权单独只读挂载，使容器中不同 UID 能读取所需文件。备份服务另有整个目录的归档权限。将该目录置于部署用户控制、未公开共享的路径下。
4. 从发布工作流的 release-manifest 填写 APP_IMAGE、AGENT_IMAGE 和 APP_RELEASE。GHCR 包如果设为私有，服务器需先通过 `docker login ghcr.io` 使用具有 read:packages 权限的凭据登录；用标准输入传入令牌，勿写入命令历史。若需要匿名拉取，由包所有者在 GitHub 的包设置中公开该包。镜像摘要必须对应已通过检查的候选提交。
5. 编辑外部凭据及域名，先运行 Compose config --quiet，再使用 sh scripts/release.sh。部署依赖的角色、数据库和内部网络由配置创建。

~~~sh
docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml config --quiet
sh scripts/release.sh
~~~

平台与被监控项目共用主机时，按实际采集量测量内存、CPU、IO及磁盘增长，再调整配置中的资源上限。示例服务提供可复现故障，接入实际项目后可按需停用。

## 域名和 HTTPS

APP_DOMAIN 填域名，不带 https://；非标准端口需显式设置相应入口。ACME_EMAIL 填证书维护邮箱。DNS A/AAAA 必须指向可达主机，80/443 应从外部到达 Caddy。APP_PUBLIC_ORIGIN 由编排生成，生产会话强制 Secure 与 MFA。

首次使用环境中的 APP_ADMIN_PASSWORD/APP_OPERATOR_PASSWORD 初始化账户。进入账户安全绑定验证器、保存单次恢复码，再设置通知邮箱。已有数据库的密码通过账户维护操作修改。部署使用专用模型密钥，已公开或共享过的密钥先完成轮换。

在实际域名下检查证书链、HTTP跳转、MFA/恢复码、退出登录和停用会话，并从机外验证访问结果。

## SMTP 与通知

production.env 配置 SMTP_HOST、SMTP_PORT、SMTP_USERNAME、SMTP_FROM，secrets/SMTP_PASSWORD 填服务商授权凭据。填写外部凭据时可临时 `chmod u+w secrets/SMTP_PASSWORD`，写入后重新运行 `python scripts/init_production.py` 还原权限；脚本保留现有值。其他外部凭据采用相同步骤。默认使用587端口并要求 STARTTLS、校验证书主机名，connect/read/write 各5秒超时。服务商使用465隐式TLS时，在主机 compose.site.yaml 的 app.environment 中配置 SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE="true"、STARTTLS_ENABLE="false"、STARTTLS_REQUIRED="false"（三个完整属性均保留 MAIL_SMTP 前缀），SSL_CHECKSERVERIDENTITY 保持开启；按服务商文档选择协议。收件邮箱在账户安全页维护。

投递过程有站内记录。临时拒绝按10秒、1分钟、5分钟最多重试三次；明确永久拒绝终止，发送结果不明不自动重发。管理员在通知中心的「邮件投递管理」查看失败与未知记录。人工重发可能产生重复，需填写依据。

Mailpit用于本地SMTP协议和业务记录测试。配置服务商后，验证实际收件、垃圾邮件归类和发送限额。

## 真实应用与观测

部署配置位于 deployment/observability。为应用增加 Prometheus/Micrometer 入口和日志采集路径，统一 service/environment 标签。Prom 抓取配置、Alloy 文件路径及标签可以用配置接入。注册数据源仅由服务端维护，服务目录只选择批准的 sourceId。不同 Compose 项目接入时使用 [专用网络和只读日志卷示例](../deployment/observability/README.md)，主机配置保存为 deployment/observability/compose.site.yaml，发布脚本会自动加载并随备份保存。

生产 Agent 通过查询网关访问数据源；该网络没有 Loki 写入端点直连。网关仅允许固定GET查询与健康路径，实际查询仍受标签、时段、记录数和响应上限约束。数据库及模型密钥不进入浏览器。

服务编辑绑定环境、sourceId、serviceLabel 与负责人，先做连接检测。随后触发一次受控实际故障，验证告警、邮件、调查和人工恢复。告警恢复不会自行关闭工单。没有样本或来源不可达时应保留失败/不完整证据。

## 模型和预算

默认 AGENT_MODE=offline。准备有效凭据后，将 OPENAI_API_KEY 写入专用secret，AGENT_MODE=holmes，COMPOSE_PROFILES=holmes，并填写 MODEL_DAILY_BUDGET_USD 与 MODEL_RESERVATION_USD。更新配置重建相应容器后，从工单人工发起。

Java在事务中预留额度，持有预留的人工调查才进入模型请求；切换至holmes模式后，已有的无预留任务由Agent返回422。上下文限制16384、输出1200、最多4步。费用预留用于平台准入，实际计费按供应商账单核对；未知费用保持冻结，管理员填写账单依据后结算。

## 初始化和启用异地备份

配置 RESTIC_REPOSITORY、RESTIC_PASSWORD 和存储服务凭据。仓库应位于独立于该主机的位置，凭据权限只覆盖专属备份前缀。仓库加密密码和恢复操作说明另存离线副本，避免主机丢失后无法解密。

首次初始化专属仓库，已有仓库跳过 init：

~~~sh
docker compose --env-file production.env -f compose.production.yaml --profile maintenance build backup
docker compose --env-file production.env -f compose.production.yaml --profile maintenance run --rm backup init
docker compose --env-file production.env -f compose.production.yaml --profile maintenance run --rm backup
~~~

备份在共享暂存卷上使用flock互斥，重叠任务返回失败，正在生成的备份保持原状。获得锁后注册退出清理，普通成功和失败路径都清除暂存明文；强制终止遗留的暂存用于排查，恢复使用通过归档摘要和隔离恢复校验的完整恢复集。恢复使用单事务导入，并重新撤销业务账号对迁移历史的写权限。

备份包含一致 pg_dump、摘要和版本记录。定时备份记录配置的 APP_RELEASE。升级前备份从当前或已停的旧 app 容器读取镜像摘要，记录为 `pre-upgrade:<旧镜像摘要>`；使用标签启动的旧容器记录实际镜像 ID。旧 app 容器已移除时标记 `pre-upgrade:unknown`。每个快照的 `/config` 包含备份当时的 production.env、两份生产 Compose 文件、heartbeat.env、secrets/、deployment/（包括主机定制的采集目标、日志标签和数据源）及 agent/holmes-observations.yaml。灾难恢复时将这些文件按原路径放回发布目录，再运行密钥初始化脚本校正权限并保留恢复值。配置内容敏感，即便是加密归档也需限制访问。工作卷使用固定暂存文件；restic历史快照不自动prune。保留/清理先预览并按所有者确认的策略执行。

安装 deployment/systemd 中 causelume-backup.service、causelume-backup.timer，启用后每12小时触发。BACKUP_HEARTBEAT_URL 指向独立的备份检查，只有备份与仓库校验成功后才发送心跳。

## 隔离恢复和回退

~~~sh
docker compose --env-file production.env -f compose.production.yaml --profile restore build restore-check
docker compose --env-file production.env -f compose.production.yaml --profile restore run --rm restore-check
docker compose --env-file production.env -f compose.production.yaml --profile restore stop restore-postgres
~~~

恢复任务只连接独立 restore 网络的临时PostgreSQL。它先校验加密仓库和归档摘要，恢复到独立库，使用迁移角色建表并验证应用角色可读取核心业务表计数。scratch数据库与恢复目录使用临时内存存储，较大备份应提前调整容量。脚本不会替换在线数据库。

首次和每月使用同一流程演练，并检查关键工单、证据和反馈。主机灾难恢复时，从备份恢复配置和secret到受限路径，初始化独立角色，恢复匹配版本数据库，通过应用登录/只读/API验证后再切换入口。使用异地仓库在新主机上演练，记录最近可恢复时间与恢复耗时，核对RPO24小时、RTO2小时的目标。

数据库已应用新迁移后，旧镜像是否可用取决于兼容性。回退需采用已验证的兼容版本或一整套恢复集，保留原失败现场。

## 机外探测

初始化脚本从 deployment/systemd/heartbeat.env.example 创建 /opt/causelume/heartbeat.env，保留已有值。填真实 PUBLIC_HEALTH_URL、EXTERNAL_HEARTBEAT_URL，权限0600。DISK_CHECK_PATH 指向实际 Docker 数据目录（用 docker info --format 检查 DockerRootDir），MIN_FREE_DISK_KIB 默认为5 GiB。安装 causelume-heartbeat.service/timer，按服务商要求设置周期和宽限。探测脚本先检查主机该分区空间、公网TLS/数据库健康，再发送心跳；任何检查失败都不发送成功心跳。应用容器的磁盘指标覆盖其工作目录所在文件系统，其他宿主分区按路径单独检查。备份年龄由独立备份心跳的周期和宽限监控，需在机外服务设定不超过24小时的告警窗口。

在独立于平台主机的探测服务中配置通知，覆盖整机停电或网络中断。分别停止平台和备份心跳，确认告警能送达。

外部资源配置完成且首次手工备份/恢复通过后，安装并启用定时任务：

~~~sh
sudo install -m 644 deployment/systemd/causelume-*.service deployment/systemd/causelume-*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now causelume-backup.timer causelume-restore.timer causelume-heartbeat.timer
systemctl list-timers 'causelume-*'
~~~

启用后，用 `journalctl -u causelume-backup.service`、`journalctl -u causelume-restore.service` 检查结果，并验证机外服务的漏心跳通知。

## 发布与升级

每次升级先看 release-manifest、安全扫描与数据迁移结果。脚本先按当前源码构建备份/恢复维护镜像，失败时不停止业务。随后停止app进入备份窗口，备份失败时保留停机状态且不执行迁移，由操作者排查后恢复。应用升级时，独立migrate任务先完成迁移及权限收紧，运行进程不持迁移密码。切换代码和 production.env 前，保留与旧版本对应的配置快照。release.sh 按 Compose 解析出的 PostgreSQL 数据卷名称判断是否首装。无卷且无运行中的数据库时直接拉取；有卷、数据库正在使用该卷时，即便 app 已停也先执行加密备份，备份成功后才拉取并更新。若数据库已停或挂载不匹配，脚本在拉取和迁移前中止。用旧版本兼容的 PostgreSQL 恢复数据库服务，核对挂载和数据后再执行升级。维护中安排停止业务写入，检查未知调查与通知。发布后核对历史数据和MFA，记录实际使用提交和镜像摘要。定期扫描依赖与镜像，补丁更新重跑直接影响的恢复与业务验收。

## 镜像发布权限排障

发布工作流使用仓库 GITHUB_TOKEN 的 packages:write 权限，镜像附带本仓库 source/revision 标签。若 GHCR 返回 denied，由包所有者在 GitHub Packages 的包设置中核对关联仓库为 W205614/Causelume，并在 Manage Actions access 中授予该仓库 Write。具体步骤见 [GitHub官方权限说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。凭据通过GitHub Secrets或标准输入提供，发布流程保留安全扫描；推送成功后，将release-manifest中的摘要填入生产配置。
