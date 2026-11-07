# v1.0.0 发布检查

检查日期：2026-11-07，Asia/Shanghai。首次发布的版本号沿用 backend/pom.xml 与 frontend/package.json 的 1.0.0。最终提交、CI 链接与源码摘要记录在 [对应 Release](https://github.com/W205614/Causelume/releases/tag/v1.0.0)。

## 版本内容与修复

本版提供账户与权限、工单协作、告警与通知、离线及可选 HolmesGPT 只读调查、冻结证据、恢复复盘、模型预算，以及单机 PostgreSQL 部署与备份恢复工具。

发布检查修复了 Windows 下两处容器生成测试文件的权限问题：发布脚本测试预先创建命令记录文件，保持宿主 ACL；隔离备份测试向当前用户 SID 显式授予临时目录的继承权限。Holmes 验证在启动退出或超时时报告具体原因。应用业务接口和数据库结构没有变更，本次没有新增数据库迁移。

新增 MIT LICENSE 和第三方组件说明，补充贡献与安全支持范围。镜像工作流仅手动触发；源码 Tag 和 Release 不会自动推送 GHCR 镜像或部署服务。观测示例的 Docker 构建上下文排除 target/。

## 本地验证

Windows 本地使用 Java 21.0.1、Maven 3.9.4、Python 3.13.12、Node.js 24.19.0、Edge 与 Docker Desktop Linux 容器。CI 使用 Java 21、Python 3.13、Node.js 22 和 Chromium。

| 检查 | 命令或方法 | 结果 |
|---|---|---|
| Java 业务、安全、任务及并发 | `mvn -B -ntp -f backend/pom.xml verify` | 17 项通过，0 失败/错误/跳过 |
| PostgreSQL 账户与迁移 | `python scripts/verify_postgres.py --maven <mvn路径>` | 2 项通过，覆盖 H2 V5→PG V9、MFA、权限与幂等性 |
| 前端交互和布局 | `npm --prefix frontend test -- --workers=1` | 16 项通过；桌面、平板、手机可导航且无横向溢出 |
| Python 调查与数据源边界 | `python -m unittest discover -s agent -p "test*.py"` | 16 项通过 |
| Java/Python/真实浏览器闭环 | `python scripts/verify_local.py` | 隔离内存库；真实 503→200、调查、反馈、复盘、账户及通知通过 |
| PostgreSQL 生产 profile 集成 | `python scripts/verify_candidate.py` | MFA、独立迁移与最小权限、Prometheus/Loki、Alertmanager、Mailpit、调查及关闭导出通过 |
| 生产备份与恢复 | 同一 candidate 测试中的真实 backup/restore 脚本 | 本地加密 restic、配置与密钥恢复、7 项计数、关闭工单及恢复库权限核对通过 |
| 独立恢复合同 | `python scripts/verify_backup.py` | pg_dump→加密 restic→校验恢复→独立数据库读回通过 |
| 发布与慢启动合同 | `python scripts/test_release.py`、`python scripts/test_candidate_ready.py` | 分别 5 项、1 项通过；Docker 外部响应由记录型替身提供 |
| 观测故障场景 | `python agent/verify_observability.py` | 不可达、错误、慢请求、依赖失败、无数据及日志注入 6 类通过 |
| Alertmanager 实际投递 | `python agent/verify_alertmanager.py` | 认证 firing/resolved、稳定 fingerprint 通过 |
| Holmes 工具与失败协议 | 固定 Holmes 0.46.0 镜像，`--network none` 执行 `agent/verify_holmes_snapshot.py` | 两项冻结工具、回环 Responses、注入阻断、503 单次请求失败通过 |
| Responses 桥接 | 固定 Holmes 镜像，断网执行 `agent/verify_responses.py` | LiteLLM→本机 Responses→工具调用通过，无供应商访问 |
| 配置与文档 | 本地/生产 Compose config、75 个相对链接、四张演示截图 | 通过；截图使用内置演示数据，本次未修改界面或重造截图 |

有限并发由现有 Java 测试覆盖重复提交、乐观版本竞争、同工单调查竞争及队列上限，不作为生产容量结论。

首次并行浏览器执行有 1 项停留在加载页超时；串行完整执行 16 项通过，没有增加自动重试或放宽断言。本机 npm 镜像不提供审计端点，改用 `npm audit --registry=https://registry.npmjs.org --audit-level=high` 完成审计。首次 Holmes 冷启动超时，补充启动故障信息后完整协议通过。

主机 Docker 默认地址池已耗尽。candidate、观测及 Alertmanager 的本地验收使用额外的 Compose IPAM 覆盖，在核对现有 Docker 子网与 Windows 路由后使用 `10.249.41.0/24`；未改动断言、生产编排、Docker 全局配置或其他项目网络。覆盖和执行记录保存在本次本地审计材料中。正常地址池可用的环境按上述原始命令运行。

## 安全、许可与发布边界

官方 npm 审计和 `pip-audit -r agent/requirements.txt` 未发现已知漏洞。本地 Trivy 使用 2026-11-07 更新且在有效期内的漏洞数据库；Agent 镜像扫描及从实际 JAR 提取的 71 个 Maven 组件扫描未发现 HIGH/CRITICAL。最终提交的完整应用镜像由 Verify 中的扫描检查；本地扫描记录与 CI 结果分别保存。

检查包括全部本地文件、构建 JAR 中的应用资源和 383 个可达历史 Git blob；按现有配置值、常见令牌及私钥模式检查，仅本地被忽略的 .env 命中预期凭据，源码与历史未发现命中。该结果限于实际检查规则。现有 .env、主机 override 和持久化数据不进入源码附件；根目录与 Agent 构建上下文使用白名单。GitHub 私密漏洞报告已重新确认启用。

维护者确认自有代码及素材可以 MIT 发布，署名 W205614。71 个 Java 运行组件的 Maven 许可元数据、Python/npm 依赖和独立服务许可已核对；H2 与 Spring Boot jarmode 的许可从对应上游版本补核。许可说明见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)，第三方组件保留原许可。此版发布源码，不附带第三方 JAR、浏览器、模型或服务镜像。

## 本地文件与 Docker 保留

清理对象为观测示例 target/、Python __pycache__/、前端 test-results/ 和历史 Surefire dumpstream，均可由源码与测试重新生成；清理前清单记录路径和大小，使用 Windows 回收站。保留当前后端构建、前端测试依赖、四张截图、历史迁移、原始配置、业务卷与有效恢复集。回收站未清空，不将移入回收站的大小视为 Windows 已释放空间。

现有日常环境为 app、agent、checkout 与可选 Holmes，业务卷为 causelume_business。仅整理本项目资源，保留旧业务镜像作为回退材料；不删除持久化卷，不执行全局 prune，不整理共享构建缓存。临时验收项目仅清理自己创建的合成数据库、日志卷和网络。

## 升级与已知限制

本次没有新迁移；现有 H2 与 PostgreSQL 仍为 V9。已有实例升级前保护数据库、.env、主机覆盖配置及 MFA 密钥；PG 升级按 [部署手册](production-setup.md) 备份、迁移、核验，涉及 schema 的回退使用验证过的完整恢复集。

本轮未重新调用付费模型；Holmes 使用真实上游实现和本机协议替身。SMTP 使用 Mailpit，restic 使用隔离的本地加密仓库。真实供应商邮件、公开 TLS、机外告警、异地恢复目标和生产容量需在实际接入环境验证。既有真实模型记录见 [上轮验证](team-release-2026-11-06.md#后续单次真实模型复验)，不替代本轮供应商验收。

GitHub Packages 与生产部署不在本次发布范围。源码 Release、Tag 与附件在同一最终提交上核对；只有该提交的必要验证和 CI 通过后正式发布。
