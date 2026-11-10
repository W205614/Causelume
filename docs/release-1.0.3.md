# v1.0.3 安全维护与镜像交付

检查日期：2026-11-10，Asia/Shanghai。本版围绕 HTTP 鉴权边界和首次 CodeQL 分析发现的问题修复，不增加业务功能或数据库迁移，schema 仍为 V9。最终源码、镜像摘要、CI 和运行结果以 [v1.0.3 Release](https://github.com/W205614/Causelume/releases/tag/v1.0.3) 为准。

## 工程修复

- 管理令牌校验与 API 请求体上限使用 Servlet 容器解码后的路径，覆盖百分号编码和分号参数，防止原始 URI 与实际处理端点不一致。
- 无会话入口由已解析的处理方法标记决定；健康检查、登录和独立 Bearer 认证的 Alertmanager 入口保留原职责，其他接口仍要求有效会话。
- 工单导出改为 `POST /api/tickets/{id}/export?format=json|markdown`，使用 JSON 请求体并经过现有 Origin 校验；保留导出权限、审计、内容与数量限制。前端按钮及集成调用方同步迁移；旧 GET 返回 405。
- 邮箱校验使用不重叠的域标签和占有量词，拒绝空域标签；MFA 密文封装长度使用受检加法；PostgreSQL 序列查找通过绑定参数传入表名和列名。
- app 保持 Java 21、Linux/amd64 和既有业务卷使用的 UID/GID 999，运行层改为固定摘要的官方 Temurin/Alpine 3.24；app、agent 均升级至已修复的 zlib 1.4-r0。对应源码采集覆盖两个实际 Alpine 运行层。
- Flyway Core/PostgreSQL 及其 CockroachDB 依赖统一为 13.12.0，H2 继续使用 2.4.240；Mockito 测试采用静态 Java agent。保留 JVM 的 CDS 优化诊断，不通过隐藏日志获得通过。

## 验证与扫描范围

本地 Java 23 项、隔离 PostgreSQL 合同 2 项、Edge 前端 17 项和镜像身份合同 4 项通过，失败、错误、跳过均为 0。真实 HTTP 回归覆盖编码路径的管理令牌、请求体限额、无会话入口、受保护端点以及导出的 GET/POST、跨站与格式约束。前端检查权限展示、拒绝时的可见错误及下载结果。

前端验证期间定位到 Python 3.13 静态测试服务默认连接队列过小导致并发模块请求拒连，测试入口改用系统连接队列；保持原并发、断言与超时后完整通过。应用不增加重试或降级。Docker 29 的 Engine ID 可以是 OCI index 摘要，候选与 Registry 内容改按实际 config 摘要和有序解压层 diff IDs 核对，不把 Engine ID 当作 config 摘要。

本轮为公开仓库启用 Dependabot 告警和 Java、JavaScript/TypeScript、Python 的 CodeQL 扩展分析。首次分析有 Java 告警 6 项，另外两种语言为 0；最终来源提交 `6dc18d68dfdaf62406e430a8296923fd5e25283f` 的三种语言扩展分析全部完成，结果均为 0，Dependabot 与 Secret scanning 活动告警也为 0。未使用 dismiss、忽略清单或降级规则。

应用镜像扫描覆盖 UNKNOWN、LOW、MEDIUM、HIGH、CRITICAL，保留发布前基线及候选的原始 JSON，不使用忽略清单或降级阈值。GitHub Actions 绿色状态与当前全部级别扫描归零分别核对。平台分析、镜像扫描和有限业务回归不能证明不存在未知漏洞或已完成生产环境验收。

旧发布镜像基线为 app 38 项（22 MEDIUM、16 LOW）、agent 1 项 MEDIUM；修复后的实际候选 app、agent 均为 0。最终检查注解没有 warning/failure；GitHub runner 系统迁移 notice 与 JVM CDS 优化诊断按原样保留，不作为应用安全告警隐藏或删除。

| 证据 | 实际结果 |
|---|---|
| [main Verify](https://github.com/W205614/Causelume/actions/runs/42788895080) / [Tag Verify](https://github.com/W205614/Causelume/actions/runs/42791118676) | 各七个作业全部通过，覆盖干净源码构建与离线业务闭环 |
| [CodeQL](https://github.com/W205614/Causelume/actions/runs/42788895271) | 三种语言完成，全部告警归零 |
| [候选构建与对应源码](https://github.com/W205614/Causelume/actions/runs/42789329313) | 原候选离线验收、全部级别扫描和完整源码采集通过 |
| [推广与默认 Compose 验收](https://github.com/W205614/Causelume/actions/runs/42791041582) | 加载原候选而非重建；双标签发布后实际拉取、启动并通过 503→调查→200→关闭 |
| 本机公开获取 | 空 Docker 登录配置实际拉取，config 摘要和有序解压层与候选一致 |

源码 ZIP 的 172 个文件直接取自最终 Git 对象，避免 Windows 换行转换。第三方对应源码 ZIP 为 352,955,556 字节，采集 209 项源材料，含 72 个 Java 库、8 个 Python 运行依赖及两个实际 Alpine 层、Temurin 和 CPython 对应材料。Release 附件 `SHA256SUMS`、`source-manifest.json` 和 `images.json` 分别记录校验、来源与镜像身份。

## 真实模型及日常运行

对本次匿名拉取的实际制品仅执行一次已授权 `gpt-6.1-sol` 隔离调查，输入仅含合成指标和脱敏日志。调查最多 4 步，每次输出上限 1200 token；实际任务成功，`modelExecution=called`，`frozen_metrics` 和 `frozen_logs` 均返回本次快照。503→200、反馈、复盘和关闭通过；真实 Edge 读取同一任务，刷新后仍可读证据与反馈，没有重复发起调查。

本次输入 8067、输出 487 token，估算费用 0.021004 美元；供应商实际结算金额仍未知，应用单次及每日各 1 美元的预算预留保留 `UNKNOWN`。预留不等于供应商硬扣费上限；未知费用不能当作已结算，也不能自动重试。隔离运行资源已结束，费用账本与调查数据库保留用于后续核对。

README 的 `investigation-v1.0.3.png` 来自上述任务于 2026-11-10 的真实 Edge 页面，使用合成账号和 checkout 数据；没有展示密钥或真实业务数据。其余四张控制台素材沿用原演示模式截图，不能当作本次模型调用证据。

本机非生产日常实例保留原配置、账号与业务卷，升级到相同镜像。健康、控制台、匿名 `/api/me` 401、管理员读取及 checkout 200 通过。新运行层先在只读复制的 H2 副本上核对 14 张表与身份序列；实际升级后的冷态导出确认所有原业务行与序列保留，仅审计由 22 增至 24（本次 LOGIN/LOGOUT）。schema 仍为 V9，四个日常容器最终恢复停止，原 Holmes 组件未升级或调用。

日常验收的首次重复拉取遇 GHCR 响应超时，当时尚未启动或写入日常库；保留失败记录后改用已完成匿名拉取和身份核验的本机制品继续验收，没有新增应用重试机制。

本轮没有验收真实云主机、公开域名/TLS、服务商 SMTP 送达、异地灾备/RPO/RTO 或长期容量；现有恢复资料保留，未声称已在新主机完成灾备重建。

## 交付约定

继续使用公开的 `ghcr.io/w205614/causelume-app` 与 `ghcr.io/w205614/causelume-agent`，各提供 `v1.0.3` 与完整来源提交号标签；checkout 复用 agent 镜像。Tag `v1.0.3` 固定在上述工程提交，根 Compose 默认拉取同版本镜像。app Registry 摘要为 `sha256:014563da9123dd0f1c4b83273cfb3ce99c3f707dbef5f5be8130152cf50d05af`，agent 为 `sha256:bb2a8d926fea0a336bae11190161bf6fc17622b8c3564a4ce2cd89a2cc6d3244`，平台均为 `linux/amd64`。

镜像、源码、第三方对应源码及清单由同一版本 Release 关联。后续 README 文档提交可以不同于镜像来源，不为此移动 Tag、重建或重发版本。

Maven 用于源码编译、测试及镜像内应用构建，不作为应用包分发入口。当前账户的 Maven 类型完整页面核对未发现 Causelume 包，唯一列出的包属于其他项目，未纳入本轮操作。v1.0.0、v1.0.1、v1.0.2 均保留正常历史职责；未发现额外草稿或候选 Tag/Release。
