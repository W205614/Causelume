# Causelume · 故障诊断与协作平台

值班人员从告警或人工报障创建工单，分派负责人，结合指标和日志调查，再记录处理、恢复验证与复盘。适用于单实例运维流程演示、开发与集成验证。

- **体验界面**　打开部署后的 `/?demo=1`。页面标记「模拟数据」，可以试派工和调查；刷新会重置样例。
- **验证完整流程**　使用默认的离线模式。示例 checkout 会真正返回 503、生成日志与指标，Python 使用规则分析，全程无需模型密钥。
- **接入模型**　HolmesGPT 的两个只读工具复查该次调查冻结的证据。模型给出推断和不确定性，由负责人核对。真实调用会产生供应商费用。

## 控制台实景

以下均为显式演示模式（`/?demo=1`）下的真实浏览器截图，使用内置样例，不含实际账户凭据或生产日志。

![运维概览：未关闭工单、近期告警和调查队列](docs/images/overview.png)

<details>
<summary>查看工单时间线、调查证据和手机布局</summary>

![工单详情及负责人处理时间线](docs/images/ticket-timeline.png)

![并排核对现场证据、诊断推断和反馈](docs/images/investigation.png)

[查看 390 像素宽的调查详情](docs/images/investigation-mobile.png)

</details>

## 本地体验与个人生产候选

本地 Docker Desktop 体验沿用 H2 和 checkout 示例：

~~~sh
python scripts/init_env.py
docker compose up -d --build --wait
~~~

打开 [控制台](http://127.0.0.1:18760)，从本机 .env 读取 admin 或 operator 初始密码。已有 .env 会保留，已有 H2 数据留在项目业务卷。升级前先备份并按 [迁移说明](backend/STORAGE.md) 核对新数据库，生产迁移不会自动覆盖本地库。

本轮还提供 Linux 单机生产候选，使用 PostgreSQL 18、强制 TOTP、邮件与站内通知、Prometheus/Alertmanager/Loki/Alloy、人工预算调查、Caddy HTTPS 和加密备份。外部资源尚未配置时，候选代码仍可在隔离环境完成验收。实测及尚未运行项见 [个人版验收](docs/personal-release.md)。

## 后续接入云主机、域名和外部服务

准备好资源后，按照下表填写配置及执行命令即可接入。真实秘密存放在受限文件中。

| 资源 | 填写位置 | 接入与验收 |
|---|---|---|
| Linux 云主机 | 部署目录 /opt/causelume，Docker Engine/Compose，防火墙 | 拉取已验证提交、下载发布镜像，检查磁盘与资源；备份旧部署后运行发布脚本。只对外发布 80/443。 |
| 域名与 TLS | production.env 的 APP_DOMAIN、ACME_EMAIL | 将 DNS 指向主机，放通 80/443，Caddy 自动申请并续期证书；访问真实域名验证 Cookie、MFA、证书与重定向。 |
| SMTP | production.env 的 SMTP_HOST/PORT/USERNAME/FROM，secrets/SMTP_PASSWORD | 使用服务商授权凭据。登录账户安全页设置收件邮箱；发出测试通知，核对实际收件和投递状态。 |
| 异地备份 | production.env 的 RESTIC_REPOSITORY，secrets/RESTIC_PASSWORD、AWS_ACCESS_KEY_ID、AWS_SECRET_ACCESS_KEY | 初始化专用 restic 仓库，执行首次备份、隔离恢复；再启用每12小时备份与每月恢复演练定时器。 |
| 外部探测 | heartbeat.env、production.env 的 BACKUP_HEARTBEAT_URL | 在机外心跳服务创建独立的运行与备份检查。配置周期/宽限，停止心跳验证告警。勿把探测服务部署在同一台主机。 |
| 真实模型 | secrets/OPENAI_API_KEY、AGENT_MODE=holmes、COMPOSE_PROFILES=holmes、预算变量 | 先轮换曾在聊天出现的旧密钥，填写预算预留和每日额度。真实调查需人工发起；供应商账单未知时额度保持冻结。 |
| 实际应用 | 服务目录和 deployment/observability 配置 | 注册 Prom 抓取目标和 Alloy 日志标签；在页面绑定批准的 prom-loki 数据源、serviceLabel 与 environment，检测连接后演练一次真实故障。 |

首次准备服务器配置：

~~~sh
cp production.env.example production.env
python scripts/init_production.py
~~~

编辑 production.env 和 secrets/ 外部凭据文件。保管 APP_MFA_KEY 与 restic 恢复密码的独立恢复副本。生产账户的首次绑定及恢复见 [账户与数据迁移](backend/STORAGE.md)。

发布工作流支持手动触发或 v* 标签。下载 release-manifest，将已验证的 app/agent 镜像摘要填入 APP_IMAGE、AGENT_IMAGE，APP_RELEASE 填提交号。发布前查看安全扫描结果，运行：

~~~sh
docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml config --quiet
sudo sh scripts/release.sh
~~~

发布脚本默认在 /opt/causelume 运行。已有实例更新时先执行加密备份，再迁移和检查健康。首次部署需要先初始化 restic 仓库，后续启用备份定时器。外部资源的详细接入、恢复及回退操作见 [生产接入手册](docs/production-setup.md)。

## 日常故障处理

1. Alertmanager 的发生事件按集成、fingerprint、startsAt 去重，自动创建并分派工单。恢复事件记录监控恢复，由负责人确认工单关闭。
2. 通知中心显示站内消息和邮件状态；管理员可检查失败及结果未知的投递。维护静默会继续记录告警。
3. 工单负责人发起只读调查，查看查询时间范围、来源、采集时间、截断和证据完整性。模型不持有生产修复权限。
4. 写入通过、失败或未知的恢复依据；完成结构化复盘、改进事项、授权导出。必要时可重新打开工单。

服务支持编辑、停用、环境、负责人、运行手册和批准数据源绑定。账户支持密码管理、TOTP、单次恢复码和会话撤销。费用核对、清理预览、MFA 和账户管理只向相应权限开放。历史数据清理仅提供预览。

## 架构与运行边界

Java 21/Spring Boot/Spring JDBC 负责业务、权限、持久化与任务；Python 与 HolmesGPT 负责采集和分析。生产采用 PostgreSQL，应用账号与迁移账号分离。查询网关只放行 Prometheus/Loki 的 GET 查询入口，观测采集接口处于内部网络。

每次调查冻结服务范围及时间窗口。来源缺失、无样本或模型失败会显式报告。真实模型默认关闭；开启后需每日额度和单次预留，费用未知需管理员核对，预算预留不等同于供应商硬账单上限。

生产候选面向单实例、个人少量项目。RPO 24小时和 RTO 2小时是待真实异地恢复演练确认的目标。容量需按首批项目负载实测。升级数据库后回退需采用兼容应用版本或完整恢复集。

## 开发与复验

~~~sh
python -m pip install -r agent/requirements.txt
npm --prefix frontend ci
cd frontend
npx playwright install chromium
npm test
cd ..
mvn -f backend/pom.xml verify
python -m unittest discover -s agent -p "test*.py"
python scripts/verify_local.py
python scripts/verify_postgres.py
python scripts/verify_backup.py
python agent/verify_observability.py
python agent/verify_alertmanager.py
python scripts/verify_candidate.py
~~~

可用 CAUSELUME_BROWSER 指定本机 Edge/Chromium，Windows 的 Maven 路径可通过 --maven 指定。候选脚本会创建有独立名称的临时测试环境，使用合成数据、Mailpit 和离线调查。生产密钥及本地业务卷不参与自动测试。

[业务 API](backend/API.md) · [账户与数据迁移](backend/STORAGE.md) · [告警与通知](backend/ALERTING.md) · [工单与复盘](backend/INCIDENTS.md) · [只读观测](agent/README.md) · [观测接入](deployment/observability/README.md) · [生产接入](docs/production-setup.md) · [候选验收](docs/personal-release.md)
