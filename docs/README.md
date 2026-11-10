# 文档导航

先在 [项目 README](../README.md) 选择本地体验或 Linux 部署，再按下面的场景阅读。

| 场景 | 文档 |
|---|---|
| 了解适用场景和部署检查 | [单机运维手册](operations.md) |
| 接入 Linux、域名、SMTP、备份和机外探测 | [生产接入手册](production-setup.md) |
| 迁移 H2、配置 PostgreSQL、账户/MFA 及恢复 | [账户与数据迁移](../backend/STORAGE.md) |
| 接入告警、维护静默、通知和投递排障 | [告警与通知](../backend/ALERTING.md) |
| 接入实际项目的指标、日志和专用网络 | [观测接入](../deployment/observability/README.md) |
| 理解调查取证、脱敏、工具与费用记录 | [调查服务](../agent/README.md) |
| 工单恢复、复盘、改进项和导出 | [工单与复盘](../backend/INCIDENTS.md) |
| 调用业务接口 | [API 契约](../backend/API.md) |
| 检查认证、Origin、配额和故障处理 | [安全说明](../backend/SECURITY.md) |
| 开发或验证控制台 | [前端说明](../frontend/README.md) |
| 核对安全修复、公开镜像、真实模型及日常升级 | [v1.0.3 发布验证](release-1.0.3.md) |
| 核对告警重投修复、镜像分发与验证范围 | [v1.0.2 发布检查](release-1.0.2.md) |
| 核对请求重投修复、验证与源码发布边界 | [v1.0.1 发布检查](release-1.0.1.md) |
| 核对首个源码版本的检查范围、修复与限制 | [v1.0.0 发布检查](release-1.0.0.md) |
| 核对本轮小团队加固、升级与清理结果 | [小团队交付记录](team-release-2026-11-06.md) |
| 查阅个人版集成测试和外部接入计划 | [个人版验证记录](personal-release.md) |
| 核对源码、容器版本与本轮文档审查 | [状态核验 · 2026-11-06](status-2026-11-06.md) |

项目建设和各阶段部署按版本记录在 [实施记录](implementation.md)、[早期验收](verification.md) 和 [增量审查](review-2026-11-06.md)。每份记录列出对应的提交、环境和结果。

当前已发布 [v1.0.3](https://github.com/W205614/Causelume/releases/tag/v1.0.3) 的源码、公开 app/agent 镜像与对应源码材料。使用者按 README 获取同版 Compose，直接拉取运行；生产按 Release 的 `images.json` 固定不可变摘要。源码 Tag 不自动推送镜像或部署服务；维护者发布新版本才使用 [手动镜像工作流](https://github.com/W205614/Causelume/actions/workflows/release.yml)。部署后记录实际运行版本。
