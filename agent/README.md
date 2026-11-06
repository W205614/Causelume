# 调查、证据与模型调用

Java 绑定服务的数据源、`service` 和 `environment` 标签，并创建调查任务。Python 向管理员已登记的地址发起只读查询，冻结采样结果，再交给离线规则或独立 HolmesGPT 分析。每份成功结果都有 `snapshotId` 和 UTC `sampledAt`，供工单查看和审计。

## 内部接口

所有目录、检测和调查接口以 `Authorization: Bearer AGENT_TOKEN` 认证。`AGENT_TOKEN_FILE` 和 `HOLMES_API_KEY_FILE` 可替代对应环境变量。接口不会返回数据源地址、文件路径或凭据。

| 方法与地址 | 请求或结果 |
|---|---|
| `GET /health` | 无凭据，仅检查服务进程 |
| `GET /sources` | `{"sources":[{"id":"prom-loki","name":"…","type":"prometheus-loki"}]}` |
| `GET /sources/{id}/check?service=example-service&environment=local` | `sourceId`、`available`、每个来源的 `source/status/detail`。检测证明查询可用；历史窗口有无样本由调查返回 |
| `POST /investigate` | `taskId,sourceId,service,environment,windowStart,windowEnd,allowModel`。时间采用带时区的 ISO 8601 格式，窗口大于零且不超过 24 小时 |
| 调查结果 | `taskId,service,sourceId,mode,modelExecution,snapshotId,sampledAt,evidence,possibleCause,uncertainty,recommendation,usage`；真实源另返回 `environment,windowStart,windowEnd,completeness,observations` |

生产的只读注册文件由 `SOURCE_REGISTRY_FILE` 指定。修改文件后重新创建 agent。`deployment/observability/sources.json` 给出本项目内部示例。外部数据源可分别在 `prometheus` 或 `loki` 节点增加 `"secretFile":"/run/secrets/reader_token"` 以发送 Bearer 凭据；请求运行时读取文件，路径限定在 `SOURCE_SECRET_DIR`（默认 `/run/secrets`）之下。注册文件只接受固定 HTTP(S) origin。API 只接受登记 ID、时间及受限标签字符，查询模板在服务端生成，拒绝重定向，不使用环境代理。部署者须保护注册文件、内部 DNS，并对外部数据源施加只读角色和网络出口限制。业务 API 不能写入注册表。生产内置源使用查询网关执行 GET 路径白名单，Python 与 Loki/Prometheus 的采集网络隔离。

## 真实源与覆盖范围

冻结前，Agent 核对 Prometheus 返回的计算时点是否等于实际窗口结束时间，并逐个核对 Loki 日志流的服务和环境标签。任一来源归属不符时，该来源记录错误并舍弃其样本，另一来源仍可独立使用。

- Prometheus 在窗口结束时评估固定 PromQL，以同一窗口内的 `up` 最小值及 Spring/Micrometer `http_server_requests_seconds_{count,sum}` 计数器增量计算请求量、5xx 增量和平均响应时间。计数器增量采用 Prometheus 外推语义，可能是小数。聚合平均不能解释尾延迟。未产生过 5xx 标签时该信号显示缺失。[Prometheus 查询接口](https://prometheus.io/docs/prometheus/latest/querying/api/)
- Loki 使用相同的标签和毫秒分辨率窗口 `(start,end]`，按时间倒序请求 4 条、最多保留 3 条，额外一条用于判定截断。仅允许受控事件枚举、日志级别、HTTP 状态、毫秒耗时和时间戳进入冻结结果；其他 JSON 字段、自由文本、流标签和未知事件内容一律移除。用户可以看到 `unclassified`。原始日志仍受源端 Loki 权限和保留设置约束。[Loki 查询接口](https://grafana.com/docs/loki/latest/reference/loki-http-api/)
- `observations.metrics`、`observations.logs` 各自保存实际查询、窗口、来源、采集时刻、状态、截断标志、样本和完整性。Prometheus 另列 `missingSignals`。空窗口、源端异常、截断、未结束窗口、指标缺失会限制结论。查询均在本次任务中冻结；在途日志仍可能稍后进入同一历史窗口。两套存储没有跨库事务快照。调查开始前固定一次采样时间。`requestedRange` 保存申请区间，`effectiveRange` 保存截至采样时间的实际区间。含未来时段时返回 `partial` 和限制说明；整个区间尚未开始时有效区间为 `null`，不查询数据源。
- 所有来源都没有样本时保留观测记录，不请求模型。已授权的 Holmes 任务此时显示 `modelExecution=skipped_no_evidence` 并报告实际模型用量 0。单个来源失败时保存另一来源的数据和 `partial`。

## 模型与费用

`AGENT_MODE=offline` 使用本地规则。启用 `holmes` 还要求每次请求由 Java 明确传入 `allowModel:true`。缺少授权立即返回 422，在读取任何日志或联系模型前终止。调用目标固定为独立 HolmesGPT 0.46.0。其唯一启用的工具集 `causelume_observations` 只提供只读的 `frozen_metrics` 与 `frozen_logs`。

应用先核验已认证的 `GET /api/info?detail=full`，再调用 `POST /api/chat`。两份结构化视图随该次 HTTP 请求以 Base64URL 传输，每份 UTF-8 原文最多 1800 字节，任务摘要最多 6144 字节。工具只解码请求内的冻结视图，没有访问源端的令牌或动态 URL。工具头在 shell 渲染前通过 URL 编码，解码时严格检查 Base64 字符。每次成功调用保存来源、调用 ID 与完整输出，且逐字核对实际返回值与本次快照。来源或内容不匹配会使调查失败。[上游头部传递](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/docs/data-sources/header-propagation.md) · [工具返回契约](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/holmes/core/models.py)

模型配置使用 `holmes-observations.yaml`。`gpt-6.1-sol` 通过 `LITELLM_ROUTE_ALL_CHAT_OPENAI_TO_RESPONSES=true` 走官方 Responses API。生产部署使用 `max_steps: 4`、`OVERRIDE_MAX_CONTENT_SIZE=16384`、`OVERRIDE_MAX_OUTPUT_TOKEN=1200` 和 `ENABLE_CONVERSATION_HISTORY_COMPACTION=false`。上下文预算包含上游框架提示和工具响应。源响应每项上限 128 KiB、Holmes 响应上限 512 KiB；最多保存 2 项初始证据、8 项实际调用结果和 3 段各不超过 2000 个码点的建议。[OpenAI 模型文档](https://developers.openai.com/api/docs/models/gpt-6.1-sol) · [Holmes 用量和上下文实现](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/holmes/core/llm.py)

`usage` 记录配置模型名、提示版本 `frozen-observations-v2` 及上游返回的汇总输入/输出令牌。缺失或仅有上游默认零值时记为 `null`。`estimatedCostUsd` 仅保留上游正数估算。Holmes 的响应不是供应商账单，因此已发生真实模型调用时 `costUsd=null`，由 Java 保留未核实金额供人工核对。只有确认未调用模型时才记 `costUsd=0`。单任务预留额约束本系统准入，不能替代供应商账户硬限额。[上游锁定版本](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/poetry.lock) · [上游许可证](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/LICENSE)

## Checkout 演示兼容

内部 checkout 和 Holmes 请求直连配置的端点。演示源返回的未知错误事件统一记录为 `unclassified`，原始事件文本不会进入工单证据或模型输入。

仅在设置 `DEMO_URL` 时登记 `checkout-demo`。旧请求省略 `sourceId` 时仍走该源，使用 `EVIDENCE_TOKEN` 读取原子 `/snapshot` 的最近 50 个请求及最新错误，请求中的历史时间范围不适用于演示源。人工以 `DEMO_CONTROL_TOKEN` 调用 `POST /control` 切换许可池占用，随后实际访问 `GET /checkout` 产生 503 或 200。其凭据与外部 Prometheus/Loki 相互独立。

## 复验

在 Python 3.13 虚拟环境运行 `python -m pip install -r agent/requirements.txt`，再从仓库根运行 `python -m unittest discover -s agent -v`。覆盖取证并发、窗口、上游故障、标签注入、重定向、保密、费用未知和未预留拒绝。执行 `python agent/verify_observability.py` 创建独立随机名的临时 Compose 项目，运行真实 Spring Boot、Prometheus、Alloy、Loki，逐项校验 `agent/evaluation-cases.json` 的 6 类事实与限制。测试端口由 Docker 分配至回环地址，结束后清除本次临时栈及其测试卷。

独立上游工具黑盒不访问外网，使用回环模型替身。PowerShell 示例：

```powershell
$fixture = (Resolve-Path agent/verify_holmes_snapshot.py).Path
$config = (Resolve-Path agent/holmes-observations.yaml).Path
docker run --rm --network none --pull never `
  --mount "type=bind,src=$fixture,dst=/contract/verify_holmes_snapshot.py,readonly" `
  --mount "type=bind,src=$config,dst=/contract/holmes-observations.yaml,readonly" `
  --entrypoint python robustadev/holmes@sha256:bec9d14eea6a8890ba498c91ab47adcce3af9b6d0c6ee26d642d4e3e0df6218e `
  /contract/verify_holmes_snapshot.py
```

如需独立验证 LiteLLM 路由，在额外虚拟环境中安装 `litellm==1.103.5 PyYAML`。部分 wheel 缺少 cl100k_base 公共词表，先设置 `CUSTOM_TIKTOKEN_CACHE_DIR`、`TIKTOKEN_CACHE_DIR` 为同一临时目录，再运行 `python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"` 准备词表。执行 `python agent/verify_responses.py` 时只允许回环流量。
