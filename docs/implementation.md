# 实施与验收

下表记录最初的演示项目建设。后续个人版集成测试和部署步骤见 [验证记录](personal-release.md) 与 [接入手册](production-setup.md)。

浏览器通过同源接口访问 Java 业务服务。Java 持有身份、服务目录、告警、事件、工单、时间线以及诊断任务；Python 接收授权的调查上下文。业务状态由单机数据库事务维护，数据保存在命名卷中。日志、用户输入及工具响应均按数据处理；处理、恢复验证和关闭由人通过业务 API 提交。

| 阶段 | 交付节点 | 验收范围 |
|---|---|---|
| 一 | 可交互的静态前端 | 登录、导航、表单、详情与角色反馈；每个模拟入口标出数据来源 |
| 二 | Java 业务 API 与同源前端 | 数据迁移、认证授权、去重、派工、版本冲突、状态转换及审计 |
| 三 | 只读诊断及任务关联 | 真实示例故障的指标与日志、证据来源、离线模式说明、上游失败、超时及人工反馈 |
| 四 | 端到端与使用文档 | 从告警到关闭和复盘；检查远端提交及工作流 |
| 五 | 本机 Docker Desktop | 检查端口与健康、持久化路径，部署后走通关键路径 |

## 参考记录

2026-11-06 通过 GitHub 仓库及公开文档核对，以下 Git 对象记录调研时的版本。

| 来源和版本 | 所见与采用范围 |
|---|---|
| [Respondr b784ffc](https://github.com/tdk-netsec14/Respondr/tree/b784ffc47efe5926ea075ab4f7c42a4be2de9257) · MIT | README 明确列出告警去重、显式状态机和事件时间线。采用这些设计，在单机数据库中实现事务及版本检查。该仓库自述仍在开发。 |
| [ruoyi-vue-pro 1d7ffe7](https://github.com/YunaiV/ruoyi-vue-pro/tree/1d7ffe74d6caf6df703af2efdb2903fba4efe4a0) · MIT | 参考用户管理与 RBAC 的职责划分。商城、多租户和工作流引擎超出范围，独立实现两个业务角色。 |
| [HolmesGPT HTTP API](https://github.com/HolmesGPT/holmesgpt/blob/131ecb19adb9db2c400444b7daf170127002f312/docs/reference/http-api.md) · Apache-2.0 | 复核独立服务 HTTP 调查接口，运行用版本和工具权限见 Agent 文档。 |
| [DeepResearch d57479e](https://github.com/W205614/DeepResearch/tree/d57479e976c9230bea54e17bf8e8cea86a39c010) | 仅查看公开示例变量和架构，借鉴服务端注入配置方式。GitHub 仓库元数据未标示许可证，未复制代码。 |
| [Alertmanager 4740369](https://github.com/prometheus/alertmanager/tree/47403693800ba753b79f61c0fbca5e7d1d74281c) · Apache-2.0 | 参考接收端区分重复投递与业务事件的语义。当前需求可由数据库唯一约束实现，无需额外运行告警路由组件。 |

前端采用浏览器原生 ES modules 和 CSS，由 Java 同源托管。上游独立服务通过发行物集成，保留原有授权。
