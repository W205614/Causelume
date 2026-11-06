# Causelume · 故障诊断与协作平台

Causelume 面向个人开发者和小团队，将告警、工单、AI 故障调查与复盘串成一条处理流程。值班人员可以结合指标和日志定位问题，协作处理故障，并保留可追溯的证据与恢复记录。

[![Verify](https://github.com/W205614/Causelume/actions/workflows/verify.yml/badge.svg)](https://github.com/W205614/Causelume/actions/workflows/verify.yml)

[文档导航](docs/README.md) · [Linux 部署](docs/production-setup.md) · [发布镜像](https://github.com/W205614/Causelume/actions/workflows/release.yml) · [运维手册](docs/operations.md)

- **体验界面**　打开部署后的 `/?demo=1`。页面标记「模拟数据」，可以试派工和调查；刷新会重置样例。
- **验证完整流程**　使用默认的离线模式。示例 checkout 会真正返回 503、生成日志与指标，Python 使用规则分析，全程无需模型密钥。
- **接入模型**　HolmesGPT 的两个只读工具复查该次调查冻结的证据。模型给出推断和不确定性，由负责人核对。真实调用会产生供应商费用。

## 适用范围与主要能力

项目采用单实例、单 worker，提供共享运维工作区。管理员维护账户、服务与派工；成员共享查看工单和诊断证据，负责人及管理员推进处理、记录复盘并导出报告。支持 Docker Desktop 本地运行和 Linux 单机部署。

| 能力 | 已实现内容 |
|---|---|
| 账户与权限 | 会话、角色、密码管理、停用与会话撤销；生产 profile 强制 TOTP 和单次恢复码 |
| 告警与通知 | Alertmanager 发生/恢复、去重、服务和环境映射、维护静默、站内消息及邮件投递状态 |
| 服务与工单 | 服务维护、负责人、筛选分页、优先级、时间线、重开、恢复依据、结构化复盘及改进项 |
| 调查与证据 | 受限 Prometheus/Loki 查询、冻结快照、来源和完整性、模型推断与人工反馈分区 |
| 费用控制 | 人工发起模型调查、每日预算预留、未知费用保留额度及人工核对 |
| 数据与交付 | H2→PostgreSQL 核对迁移、生产编排、加密备份、隔离恢复、平台指标、CI 扫描及摘要发布 |

## 控制台实景

以下截图来自演示模式（`/?demo=1`），使用内置样例数据。

![运维概览：未关闭工单、近期告警和调查队列](docs/images/overview.png)

<details>
<summary>查看工单时间线、调查证据和手机布局</summary>

![工单详情及负责人处理时间线](docs/images/ticket-timeline.png)

![并排核对现场证据、诊断推断和反馈](docs/images/investigation.png)

[查看 390 像素宽的调查详情](docs/images/investigation-mobile.png)

</details>

## 快速开始

本地使用 Docker Desktop、H2 和 checkout 示例服务。首次运行时生成 `.env` 并启动：

~~~sh
python scripts/init_env.py
docker compose up -d --build --wait
~~~

打开 [控制台](http://127.0.0.1:18760)，使用 `.env` 中的 admin 或 operator 初始密码登录。业务数据保存在 Docker 命名卷中。

已有 `.env` 时跳过初始化，沿用现有账户和数据。升级前备份数据库并在副本上验证迁移，更新镜像后重新创建容器。操作步骤见 [数据迁移](backend/STORAGE.md) 和 [运维手册](docs/operations.md)。

本机配置可放在不入库的 `compose.override.yaml`。Compose 默认自动合并它；使用 `-f` 时同时列出所需的覆盖文件。`AGENT_MODE=offline` 使用离线规则，`holmes` 使用真实模型。

Linux 单机部署使用 PostgreSQL 18、TOTP、邮件与站内通知、Prometheus/Alertmanager/Loki/Alloy、Caddy HTTPS 和加密备份。按下节配置外部服务后，可接入实际项目。

## 部署与外部服务接入

Linux 部署需要准备主机、域名和相应服务账户，按下表填写配置。凭据存放在受限文件中。

| 资源 | 填写位置 | 接入与验收 |
|---|---|---|
| Linux 云主机 | 部署目录 /opt/causelume，Docker Engine/Compose，防火墙 | 拉取已验证提交、下载发布镜像，检查磁盘与资源；备份旧部署后运行发布脚本。只对外发布 80/443。 |
| 域名与 TLS | production.env 的 APP_DOMAIN、ACME_EMAIL | 将 DNS 指向主机，放通 80/443，Caddy 自动申请并续期证书；访问真实域名验证 Cookie、MFA、证书与重定向。 |
| SMTP | production.env 的 SMTP_HOST/PORT/USERNAME/FROM，secrets/SMTP_PASSWORD | 使用服务商授权凭据。登录账户安全页设置收件邮箱；发出测试通知，核对实际收件和投递状态。 |
| 异地备份 | production.env 的 RESTIC_REPOSITORY，secrets/RESTIC_PASSWORD、AWS_ACCESS_KEY_ID、AWS_SECRET_ACCESS_KEY | 初始化专用 restic 仓库，执行首次备份、隔离恢复；再启用每12小时备份与每月恢复演练定时器。 |
| 外部探测 | heartbeat.env、production.env 的 BACKUP_HEARTBEAT_URL | 使用机外心跳服务检查平台运行和备份，配置周期与宽限，并验证心跳停止后的告警。 |
| 真实模型 | secrets/OPENAI_API_KEY、AGENT_MODE=holmes、COMPOSE_PROFILES=holmes、预算变量 | 使用专用密钥，填写每日额度和单次预留。调查由人发起，按供应商计费；未知费用保留额度待核对。 |
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
3. 工单负责人发起只读调查，核对证据、可能原因和建议，再执行处理操作。调查详情保留查询范围、来源、采集时间和完整性。
4. 写入通过、失败或未知的恢复依据；完成结构化复盘、改进事项、授权导出。必要时可重新打开工单。

服务支持编辑、停用、环境、负责人、运行手册和批准数据源绑定。账户支持密码管理、TOTP、单次恢复码和会话撤销。费用核对、清理预览、MFA 和账户管理只向相应权限开放。历史数据清理仅提供预览。

## 系统架构

Java 21/Spring Boot/Spring JDBC 负责业务、权限、持久化与任务；Python 与 HolmesGPT 负责采集和分析。Linux 部署采用 PostgreSQL，由独立迁移任务升级数据库，业务进程使用单独的运行账户。查询网关提供 Prometheus/Loki 的只读入口，观测组件通过内部网络连接。通知通过事务 Outbox 投递，调查 worker 在 Java 进程内运行。

Linux 部署的服务连接如下：

```mermaid
flowchart LR
    User[浏览器] --> Caddy[HTTPS 入口]
    Caddy --> Java[Java 业务与权限]
    Java --> DB[(PostgreSQL)]
    AM[Alertmanager] -->|独立令牌| Java
    Prom[Prometheus] --> AM
    Alloy[Alloy 日志采集] --> Loki[Loki]
    Java -->|授权调查| Agent[Python 调查服务]
    Agent --> Gateway[只读查询网关]
    Gateway --> Prom
    Gateway --> Loki
    Agent -->|冻结证据| Holmes[可选 HolmesGPT]
    Holmes --> Model[外部模型]
    Java --> Outbox[事务通知 Outbox]
    Outbox --> SMTP[外部 SMTP]
    Backup[备份与恢复任务] --> DB
    Backup --> Restic[加密异地仓库]
```

每次调查冻结服务范围和时间窗口，并展示来源缺失、样本截断及模型执行状态。平台预算控制调查准入，实际费用以供应商账单核对。备份策略、恢复目标和扩容条件见 [运维手册](docs/operations.md)。

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

可用 `CAUSELUME_BROWSER` 指定本机 Edge/Chromium，Windows 的 Maven 路径可通过 `--maven` 指定。自动化验收使用独立测试数据、Mailpit 和离线调查。

测试覆盖浏览器交互、业务 API、数据库迁移、告警通知、观测取证、模型工具协议及备份恢复。离线与真实模型完整流程的运行结果见 [验证记录](docs/team-release-2026-11-06.md)。

## 常见问题

- **已有 `.env`，初始化报 FileExistsError？** 跳过初始化，保留原配置；脚本不会覆盖已有凭据。
- **本地运行需要云主机、域名或 SMTP 吗？** 不需要，按快速开始使用 Docker Desktop 即可。配置 SMTP 后可将站内通知同时投递到邮箱。
- **如何切换模型模式？** 默认使用 `AGENT_MODE=offline`。设置模型密钥、预算、`AGENT_MODE=holmes` 和 `COMPOSE_PROFILES=holmes` 后，重新创建容器；本机覆盖文件中的模式设置也需同步调整。
- **如何反馈问题？** 提供提交号或镜像摘要、复现步骤和脱敏日志。配置排查见 [安全说明](backend/SECURITY.md) 和 [运维手册](docs/operations.md)。

更多配置、接口和操作说明见 [文档导航](docs/README.md)。
