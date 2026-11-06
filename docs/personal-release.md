# 个人版候选验证（2026-11-06）

本轮起点为 7b6f2d4，面向单机个人运维。外部云资源尚未配置，交付代码、配置、发布入口与接入手册。

## 已验证范围

- 阶段一独立快照：真实 PostgreSQL18.6 账号、MFA、撤销会话、重复写、分页与任务收尾；合成 H2 V5 文件导出、PG原子导入、坏快照回退、非空库拒绝与序列校验。保留旧迁移校验值。
- 阶段二：真实HTTP并发告警发生/恢复、身份认证、重复投递、乱序和维护静默；真实SMTP协议测试包括接受、临时拒绝、永久拒绝和DATA后断连。结果不明保留UNKNOWN。
- 阶段三：真实 Spring Boot/Micrometer、Prometheus、Alloy、Loki 的六类故障评测；真实 Alertmanager Bearer发生/恢复；固定 Holmes官方镜像在断网容器内连接回环模型替身，验证冻结工具、注入阻断与用量聚合。真实费用未知。
- 阶段四：工单重开、三态恢复、结构化复盘、改进项、服务绑定/停用、授权导出和只读清理预览。真实浏览器还验证了MFA绑定、恢复码、通知邮箱、预算和通知页。
- 阶段五：独立 prod-profile候选环境运行真实PostgreSQL、两角色最小权限、TOTP、Alertmanager、Mailpit、Prom/Loki/Alloy和离线Agent。从真实发生告警走到邮件、调查、恢复事件、人工验证、复盘关闭和导出。

管理端口独立子上下文最初漏注册过滤器，已修复并实测匿名401/带令牌指标可读。恢复脚本首次暴露pg_restore参数及管道错误掩盖，已改为独立命令并在真实镜像重跑。脚本/配置的首次失败均未作用于现有业务库。

## 交付阶段

| 阶段 | 提交 | 范围 |
|---|---|---|
| 一 | d729707、bce8de3 | PostgreSQL、迁移、账户和 MFA |
| 二 | 8b74b35 | 真实告警、事务通知和邮件状态 |
| 三 | b920a86 | 真实观测、冻结证据和调查预算 |
| 四 | e402d84 | 服务维护、工单闭环与控制台 |
| 五 | cd8bd58 及后续修正 | 生产编排、备份恢复、发布、安全扫描和接入手册 |

前四阶段提交对应的 GitHub Actions 已通过。阶段五首次 CI 暴露扫描脚本变量展开、秘密扫描远程依赖解析及 Linux 测试恢复目录归属问题；均保留失败记录，修正后的结果以当前提交的 [Verify 工作流](https://github.com/W205614/Causelume/actions/workflows/verify.yml) 为准。候选链路在本机及首次 CI 均通过六项验证；独立恢复测试修正后也在真实 Linux 非特权 UID 下验证。

最终部署复核还覆盖多 UID 凭据挂载、带认证的 S3 协议替身初始化、观测绑定与宿主磁盘阈值。它们使用隔离合成环境，未连接真实云资源。

## 外部接入后补验

| 项目 | 当前状态 | 接入后验收 |
|---|---|---|
| 云主机、域名与公开TLS | 实现及配置已交付，外部未运行 | DNS/证书/防火墙/MFA/入口与内部端口 |
| 实际服务商SMTP | Mailpit和协议异常已测 | 实际邮箱送达、垃圾邮件与供应商限额 |
| 异地备份及新主机恢复 | 本地加密仓库与独立数据库恢复已测 | 真正异地读写、角色与应用恢复、RPO/RTO计时 |
| 机外健康/备份心跳 | 脚本与定时器已交付 | 断联、停止进程、停止备份能通知 |
| 真实模型 | 本轮未调用 | 新密钥、预算和明确授权后单独验证 |
| 容量 | 有边界和资源限制 | 按首批实际项目测延迟、内存、IO与磁盘增长 |

镜像扫描随后拦截了旧 Spring Boot 管理的 Tomcat/Jackson 以及基础镜像 APT 漏洞。按 [Boot 4.1.2](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.2)、[Tomcat安全公告](https://tomcat.apache.org/security-11.html)、[Jackson 3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) 升级同系列补丁；主应用和观测样例实际JAR均核对依赖。Ubuntu APT 使用 [官方修复版本2.4.15ubuntu0.1](https://ubuntu.com/security/CVE-2026-109518)，更新阶段启用HTTPS并关闭该漏洞涉及的下载重试。扫描仍保留HIGH/CRITICAL阻断门槛。

安全扫描结果以对应 GitHub工作流和报告为准。镜像发布工作流在扫描通过后才推送，不把功能验证当成漏洞扫描。

## 操作入口

[外部接入](production-setup.md) · [账户/迁移](../backend/STORAGE.md) · [告警](../backend/ALERTING.md) · [复盘](../backend/INCIDENTS.md) · [采集与评测](../deployment/observability/README.md)

本轮未清理现有文件、业务卷或原部署。测试创建的独立临时容器和数据仅用于合成验收。
