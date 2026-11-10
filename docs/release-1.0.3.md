# v1.0.3 安全维护与镜像交付

检查日期：2026-11-10，Asia/Shanghai。本版围绕 HTTP 鉴权边界和首次 CodeQL 分析发现的问题修复，不增加业务功能或数据库迁移，schema 仍为 V9。最终源码、镜像摘要、CI 和运行结果以 [v1.0.3 Release](https://github.com/W205614/Causelume/releases/tag/v1.0.3) 为准。

## 工程修复

- 管理令牌校验与 API 请求体上限使用 Servlet 容器解码后的路径，覆盖百分号编码和分号参数，防止原始 URI 与实际处理端点不一致。
- 无会话入口由已解析的处理方法标记决定；健康检查、登录和独立 Bearer 认证的 Alertmanager 入口保留原职责，其他接口仍要求有效会话。
- 工单导出改为 `POST /api/tickets/{id}/export?format=json|markdown`，使用 JSON 请求体并经过现有 Origin 校验；保留导出权限、审计、内容与数量限制。前端按钮及集成调用方同步迁移；旧 GET 返回 405。
- 邮箱校验使用不重叠的域标签和占有量词，拒绝空域标签；MFA 密文封装长度使用受检加法；PostgreSQL 序列查找通过绑定参数传入表名和列名。

## 验证与扫描范围

本地 Java 23 项、隔离 PostgreSQL 合同 2 项、Edge 前端 17 项和镜像身份合同 4 项通过，失败、错误、跳过均为 0。真实 HTTP 回归覆盖编码路径的管理令牌、请求体限额、无会话入口、受保护端点以及导出的 GET/POST、跨站与格式约束。前端检查权限展示、拒绝时的可见错误及下载结果。

前端验证期间定位到 Python 3.13 静态测试服务默认连接队列过小导致并发模块请求拒连，测试入口改用系统连接队列；保持原并发、断言与超时后完整通过。应用不增加重试或降级。Docker 29 的 Engine ID 可以是 OCI index 摘要，候选与 Registry 内容改按实际 config 摘要和有序解压层 diff IDs 核对，不把 Engine ID 当作 config 摘要。

本轮为公开仓库启用 Dependabot 告警和 Java、JavaScript/TypeScript、Python 的 CodeQL 扩展分析。首次分析有 Java 告警 6 项，另外两种语言为 0；这些数字是修复前基线，不能当作最终结果。最终复扫需对应发布来源，活动告警数量和三种语言分析结果见 Release 关联记录。

应用镜像扫描覆盖 UNKNOWN、LOW、MEDIUM、HIGH、CRITICAL，保留发布前基线及候选的原始 JSON，不使用忽略清单或降级阈值。GitHub Actions 绿色状态与当前全部级别扫描归零分别核对。平台分析、镜像扫描和有限业务回归不能证明不存在未知漏洞或已完成生产环境验收。

## 交付约定

继续使用公开的 `ghcr.io/w205614/causelume-app` 与 `ghcr.io/w205614/causelume-agent`，提交号标签关联实际构建来源；checkout 复用 agent 镜像。镜像、源码、第三方对应源码及清单由同一版本 Release 关联，实际来源提交和不可变摘要单独记录，不移动已有公开版本。

Maven 用于源码编译、测试及镜像内应用构建，不作为应用包分发入口。当前账户的 Maven 类型完整页面核对未发现 Causelume 包，唯一列出的包属于其他项目，未纳入本轮操作。v1.0.0、v1.0.1、v1.0.2 均保留正常历史职责；未发现额外草稿或候选 Tag/Release。
