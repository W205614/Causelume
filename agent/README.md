# 调查服务与可复现故障

Java 创建任务并保存状态，通过内网调用 investigator。调查只访问已登记的 checkout。checkout 由 Waitress 提供 HTTP 服务，使用容量为一的许可池模拟一个受限依赖。人工启用故障会占住该许可，随后访问 `GET /checkout` 会实际返回 HTTP 503，并留下带请求 ID、时间、状态和事件名的日志。人工解除占用后，需要重新请求才能产生恢复证据。

## 接口

| 接口 | 身份 | 结果 |
|---|---|---|
| `POST /investigate` | `Bearer AGENT_TOKEN` | 输入 `{"taskId":"123","service":"checkout"}`。成功返回任务 ID、模式、证据数组、可能原因、不确定性和人工建议 |
| `GET /metrics`、`GET /logs` | `Bearer EVIDENCE_TOKEN` | 当前窗口样本，最多 50 个请求。切换故障状态、重启演示服务都会开启新窗口 |
| `POST /control` | `Bearer DEMO_CONTROL_TOKEN` | `{"fault":true}` 注入连接池故障，`false` 解除 |
| `GET /checkout` | 演示客户端 | 发起一次业务请求，返回 HTTP 200 或 503 |
| `GET /health` | 健康探针 | 服务进程可用。业务恢复应另行访问 `/checkout` 验证 |

只有 `/investigate` 与自身的 `/health` 由 investigator 提供。其余接口属于 checkout。证据格式为 `{"source":"checkout:/logs","detail":"时间、请求 ID、HTTP 状态与事件"}`。无请求样本或未登记服务返回 422；认证失败 401，格式错误 400，依赖错误 502，依赖超时 504。错误体为 `{"error":"原因"}`。服务端只发起固定目标的请求，拒绝重定向，不接受用户自定义网址或命令。

## 两种运行模式

**offline** 先读取指标；窗口有 5xx 时再读取日志。规则只在出现 `pool_exhausted` 记录时提出连接池方向的推测。没有错误样本时明确告知当前观察范围。这是离线演示，证据来自实际演示请求。

**holmes** 在采集证据后，请求独立 HolmesGPT 0.46.0 服务。先通过已认证的 `GET /api/info?detail=full` 检查唯一启用的工具集及工具数量，再调用 `POST /api/chat`。模型可使用两个无参数只读工具复查日志和指标。应用解析 `analysis` 中的结构化 JSON，证据字段始终来自采样。额外工具、无效返回、认证失败或超时会结束任务。观测及配置检查各 3 秒，模型响应读取超时 80 秒，无应用级自动重试。[上游 HTTP 接口](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/docs/reference/http-api.md) · [服务实现](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/server.py)

Holmes 使用上游 Apache-2.0 镜像 `robustadev/holmes:0.46.0`；Docker Hub 在 2026-11-06 报告该标签大小约 293 MB。项目通过 HTTP 复用现有框架，自行编写两个只读工具。两个固定脚本入口均使用证据读取令牌，命令字符串没有令牌值。故障控制令牌只注入 checkout。集成不挂载 kubeconfig、云凭据或 Docker socket。[上游许可证](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/LICENSE) · [上游工具配置](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/docs/data-sources/custom-toolsets.md) · [镜像元数据](https://hub.docker.com/v2/repositories/robustadev/holmes/tags/0.46.0)

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
