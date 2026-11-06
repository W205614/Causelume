# 调查服务与可复现故障

Java 创建任务并保存状态，通过内网调用 investigator。调查只访问已登记的 checkout。checkout 由 Waitress 提供 HTTP 服务，使用容量为一的许可池模拟一个受限依赖。人工启用故障会占住该许可，随后访问 `GET /checkout` 会实际返回 HTTP 503，并留下带请求 ID、时间、状态和事件名的日志。人工解除占用后，需要重新请求才能产生恢复证据。

## 接口

| 接口 | 身份 | 结果 |
|---|---|---|
| `POST /investigate` | `Bearer AGENT_TOKEN` | 输入 `{"taskId":"123","service":"checkout"}`。成功返回任务 ID、模式、快照标识 `snapshotId`、UTC 采样时间 `sampledAt`、证据数组、可能原因、不确定性和人工建议 |
| `GET /metrics`、`GET /logs` | `Bearer EVIDENCE_TOKEN` | 当前窗口样本，最多 50 个请求。切换故障状态、重启演示服务都会开启新窗口 |
| `GET /snapshot` | `Bearer EVIDENCE_TOKEN` | 在同一把锁下读取窗口请求数、5xx 数和最近一条失败日志，并生成唯一快照 ID。每次调查只采样一次 |
| `POST /control` | `Bearer DEMO_CONTROL_TOKEN` | `{"fault":true}` 注入连接池故障，`false` 解除 |
| `GET /checkout` | 演示客户端 | 发起一次业务请求，返回 HTTP 200 或 503 |
| `GET /health` | 健康探针 | 服务进程可用。业务恢复应另行访问 `/checkout` 验证 |

只有 `/investigate` 与自身的 `/health` 由 investigator 提供。其余接口属于 checkout。证据格式为 `{"source":"checkout:/logs","detail":"时间、请求 ID、HTTP 状态与事件"}`。无请求样本或未登记服务返回 422；认证失败 401，格式错误 400，依赖错误 502，依赖超时 504。错误体为 `{"error":"原因"}`。服务端只发起固定目标的请求，拒绝重定向，不接受用户自定义网址或命令。

## 两种运行模式

**offline** 在一次原子快照内读取指标，并按错误计数选择是否引用该窗口最近的失败日志。规则只在出现 `pool_exhausted` 时提示连接池方向。窗口内没有 5xx 时，报告写明当前观察范围。切换故障状态和后续访问不会改变已取得的快照。

**holmes** 先核验 `GET /api/info?detail=full` 的鉴权和工具白名单，再向 `POST /api/chat` 提交本次快照摘要。两个无参数工具分别返回该次采样的计数和最近一条错误日志。应用把精简 JSON 视图编码在本次 HTTP 请求的独立头部，经 Holmes 的 request context 传入。工具只解码自身视图，无访问 checkout 所需的网络凭据、无全局快照缓存，模型不能指定其他调查的 ID。工具参数插入 shell 之前先 URL 编码，固定的 Python 命令只接受十六进制负载。[Holmes 0.46.0 请求头上下文](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/docs/data-sources/header-propagation.md)

初始证据的逻辑来源为 `checkout:/metrics` 和 `checkout:/logs`，实际由一次 `/snapshot` 提供。每次成功工具调用另存一项证据，来源为 `holmes:checkout_metrics` 或 `holmes:checkout_logs`，内容包括上游调用 ID 与实际返回的完整只读视图。应用逐次核对返回内容与本次快照；发生错配、工具报错或超限时，整项调查失败。模型回复只写入可能原因、不确定性与人工建议三个字段。页面可用顶层 `snapshotId`、`sampledAt` 关联证据；升级前的历史记录可能缺少这两个字段。[上游返回结构](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/holmes/core/models.py)

调查输入上限为 4 KiB，快照响应 4 KiB、工具配置响应 128 KiB、模型服务总响应 512 KiB。每次最多持久化 2 条采样证据与 8 次工具查询结果，每项分析正文限 2000 个 Unicode 码点。观测与配置请求各限时 3 秒，模型读取超时为 80 秒。读取连接超时、无效数据和异常响应分别向 Java 报告，不发起应用级自动重试。

Holmes 使用 Apache-2.0 上游镜像 `robustadev/holmes:0.46.0`，Docker Hub 在 2026-11-06 报告该标签大小约 293 MB。项目通过独立 HTTP 服务复用上游框架。启用快照模式时使用 `holmes-snapshot.yaml`，仅 agent 与业务后端使用证据读取令牌访问 checkout，故障控制令牌由 checkout 核验。模型进程只需鉴权与模型凭据。[上游许可证](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/LICENSE) · [镜像元数据](https://hub.docker.com/v2/repositories/robustadev/holmes/tags/0.46.0)

模型为 `gpt-6.1-sol`，官方服务地址为 `https://api.openai.com/v1`。Holmes 配置采用 `openai/gpt-6.1-sol`，并向其进程设置 `LITELLM_ROUTE_ALL_CHAT_OPENAI_TO_RESPONSES=true`。Holmes 锁定的 LiteLLM 1.103.5 将带函数工具的 completion 调用转换为 `POST /v1/responses`。真实调用需要用户在服务端配置 `OPENAI_API_KEY` 并承担实际请求费用。[OpenAI 模型文档](https://developers.openai.com/api/docs/models/gpt-6.1-sol) · [Holmes 依赖锁](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/poetry.lock) · [LiteLLM 路由实现](https://github.com/BerriAI/litellm/blob/v1.103.5/litellm/main.py)

## 本地验证

在本目录的 Python 3.13 虚拟环境中执行 `python -m pip install -r requirements.txt`、`python -m unittest -v`。这些测试通过实际 HTTP 读取演示数据，对模型异常、无证据、认证、工具白名单和恢复进行检查。模型响应由本地 HTTP 替身提供。

单独验证模型协议时，在隔离环境安装 `litellm==1.103.5` 与 `PyYAML`。部分平台的 wheel 缺少 cl100k_base 公共词表，先准备缓存，再运行断网验证。在 PowerShell 中执行以下命令。词表由 tiktoken 下载并校验哈希，无需模型凭据。

```powershell
$env:CUSTOM_TIKTOKEN_CACHE_DIR = Join-Path $env:TEMP 'causelume-agent-046-tokenizer'
$env:TIKTOKEN_CACHE_DIR = $env:CUSTOM_TIKTOKEN_CACHE_DIR
python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"
python verify_responses.py
```

验证脚本只允许回环连接，核验真实桥接包发出的 URL、模型名、函数工具以及响应到工具调用的转换。真实模型能力与账户权限需要另行验证。


使用固定版本的上游镜像验证真实 Holmes 工具链时，在仓库根目录运行下面的 PowerShell 命令。只挂载新配置和测试脚本，使用假凭据与容器内回环模型替身，不加入项目网络。脚本检查正常工具调用的两个原始结果，并用恶意请求头验证 shell 注入被拦截。

```powershell
$fixture = (Resolve-Path agent/verify_holmes_snapshot.py).Path
$config = (Resolve-Path agent/holmes-snapshot.yaml).Path
docker run --rm --network none --pull never `
  --mount "type=bind,src=$fixture,dst=/contract/verify_holmes_snapshot.py,readonly" `
  --mount "type=bind,src=$config,dst=/contract/holmes-snapshot.yaml,readonly" `
  --entrypoint python robustadev/holmes@sha256:bec9d14eea6a8890ba498c91ab47adcce3af9b6d0c6ee26d642d4e3e0df6218e `
  /contract/verify_holmes_snapshot.py
```
