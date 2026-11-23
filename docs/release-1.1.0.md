# v1.1.0 真实模型补验

2026-11-23（北京时间），v1.1.0 正式镜像通过一次真实 OpenAI `gpt-6.1-sol` 调查。发布时已验证离线流程和回环模型协议，本次补验覆盖供应商调用。 [发布说明](https://github.com/W205614/Causelume/releases/tag/v1.1.0)

## 制品与环境

- app 和 agent 使用公开镜像的不可变摘要，镜像来源为 `99fe93ac2b0924a01d7b606f70f34969db823c46`。HolmesGPT 使用仓库固定的 `0.46.0` 镜像和 v1.1.0 的只读工具配置。 [Release 镜像清单](https://github.com/W205614/Causelume/releases/download/v1.1.0/images.json)
- 独立 Docker Compose 项目使用新建 H2 数据库和真实 Prometheus、Loki 服务。指标与日志均为合成的 HTTP 503 样本，日志另含一条位于所选时间窗之外的记录。

## 验收结果

| 检查 | 实际结果 |
| --- | --- |
| 取证预览与数据源 | 预览显示 Holmes 模式和已绑定观测源，Prometheus、Loki 均返回有匹配样本。 |
| 自定义取证窗口 | 请求窗口与实际采集窗口一致。指标显示 5xx 计数增加，Loki 保留窗口内三条脱敏 503 日志并标记截断，窗口外的记录被排除。 |
| 供应商调用与工具 | 调查状态为 `SUCCEEDED`，`modelExecution=called`。结果包含 `frozen_metrics` 和 `frozen_logs` 的实际工具返回。 |
| 诊断内容 | 模型指出 5xx 增加和日志截断，说明证据无法区分 503 的根因或确认恢复，并建议人工回查原观测源。 |
| 持久化 | 保存人工反馈后重新登录，读回同一份调查结果和反馈。 |
| 预算账本 | 记录一笔 0.16 美元应用预留，费用估算为 0.023318 美元。供应商实际费用待核对，账本保留 `UNKNOWN`。 |

这次补验覆盖一个合成故障在隔离环境中的调查链路。诊断建议仍需人工核对。生产外部接入的验收步骤见 [Linux 接入手册](production-setup.md)。
