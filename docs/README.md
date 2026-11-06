# 文档导航

先在 [项目 README](../README.md) 选择本地演示或个人生产候选，再按下面的场景阅读。历史验收只证明记录中的版本、环境和步骤。

| 场景 | 文档 |
|---|---|
| 判断是否符合使用范围 | [单机运维与上线边界](operations.md) |
| 接入 Linux、域名、SMTP、备份和机外探测 | [生产接入手册](production-setup.md) |
| 迁移 H2、配置 PostgreSQL、账户/MFA 及恢复 | [账户与数据迁移](../backend/STORAGE.md) |
| 接入告警、维护静默、通知和投递排障 | [告警与通知](../backend/ALERTING.md) |
| 接入实际项目的指标、日志和专用网络 | [观测接入](../deployment/observability/README.md) |
| 理解调查取证、脱敏、工具与费用边界 | [调查服务](../agent/README.md) |
| 工单恢复、复盘、改进项和导出 | [工单与复盘](../backend/INCIDENTS.md) |
| 调用业务接口 | [API 契约](../backend/API.md) |
| 检查认证、Origin、配额和错误边界 | [安全边界](../backend/SECURITY.md) |
| 开发或验证控制台 | [前端说明](../frontend/README.md) |
| 核对个人版候选的证据与未运行项 | [候选验收](personal-release.md) |
| 核对源码、容器版本与本轮文档审查 | [状态核验 · 2026-11-06](status-2026-11-06.md) |

原始建设与本地部署历史保留在 [实施记录](implementation.md)、[早期验收](verification.md) 和 [增量审查](review-2026-11-06.md)。它们不能替代当前版本的发布与上线检查。

发布优先使用通过验证的提交及 [发布清单](https://github.com/W205614/Causelume/actions/workflows/release.yml) 中的不可变镜像摘要。当前没有承诺企业支持周期或安全响应 SLA。
