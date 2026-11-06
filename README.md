# Causelume · 故障诊断与协作平台

运维人员可以从告警建单，派给负责人，查看日志和指标，请只读 Agent 收集证据，最后人工验证恢复并关闭工单。浏览器、Java API、Python 调查服务与可注入故障的 checkout 服务组成一条可在单机重复运行的链路。

## 从 Docker 启动

需要 Docker Desktop（Linux containers）、Docker Compose 和 Python 3。默认只发布两个回环地址。首次启动在仓库根目录执行：

~~~sh
python scripts/init_env.py
docker compose up -d --build --wait
docker compose ps
~~~

打开 [本地控制台](http://127.0.0.1:18760)。使用 admin 或 operator，首次初始化的密码在本地 .env 中分别对应 APP_ADMIN_PASSWORD 和 APP_OPERATOR_PASSWORD。初始化脚本使用系统随机数生成不同凭据，遇到已有文件会停止。数据库保存在 causelume_business 命名卷。以后修改初始化变量不会重置数据库内已有账户。

| 操作 | 命令 |
|---|---|
| 检查数据库连接 | 打开 [健康接口](http://127.0.0.1:18760/api/health) |
| 运行完整故障演示 | python scripts/e2e.py --env-file .env |
| 停止本项目 | docker compose stop |
| 恢复已创建的容器 | docker compose start |
| 查看日志 | docker compose logs --tail 100 app agent checkout |

完整演示使用当前账号创建可追踪的真实业务记录，并独占示例 checkout 故障开关。请串行运行。脚本注入连接池故障，实测 HTTP 503，读取服务指标和日志，重复投递同一条告警以验证去重，然后建单并派给 operator。脚本以负责人账号发起调查、核对两份证据并记录验收反馈。实际使用时由负责人完成这些步骤。脚本以单独的演示控制凭据解除故障，实测 HTTP 200 后写入验证与复盘。在 holmes 模式下，显式添加 --expect-mode holmes 会发起一次付费调查。运行后可按脚本输出的工单号和任务号在页面核对。手工操作时，在告警中心建单，管理员在工单详情派工，负责人开始处理后发起调查，查看结果并提交人工反馈。

## 职责和数据

- 浏览器原生 ES modules，由 Java 同源提供。默认接入 /api。独立的前端交互样例使用模拟数据，入口和测试见 [前端说明](frontend/README.md)。
- Java 21、Spring Boot、Spring JDBC、Flyway 和 H2。Java 管理会话、账号角色、服务目录、告警、工单、任务、审计与复盘。库内唯一约束处理重复告警和重投，工单版本防止后写入者覆盖他人操作。一个工单同时承载事件描述与处理记录。接口和状态转换见 [业务 API](backend/API.md)。
- Python 调查服务只读获取指定来源的指标和日志。默认 offline 模式按已观测指标决定是否调取错误日志，返回包含时间和来源的证据、可能原因、不确定性与人工建议。这里的 503 与日志来自示例服务实际处理的请求，结论来自确定性规则。演示服务只保留最近 50 次请求，切换故障状态会开启新采样窗口。
- 可选 HolmesGPT 通过私有 HTTP 接口连接模型。模型凭据只注入 Holmes 容器。平台保留任务与证据，Agent 无权对工单做状态转换，也不持有注入或解除示例故障的凭据。模型配置及验证边界见 [Agent 说明](agent/README.md)。

## 配置

| 变量 | 用途 |
|---|---|
| APP_ADMIN_PASSWORD / APP_OPERATOR_PASSWORD | 首次初始化账户 |
| AGENT_TOKEN | Java 到 Python 调查服务 |
| EVIDENCE_TOKEN | 读取 checkout 的指标和日志 |
| DEMO_CONTROL_TOKEN | 仅供演示操作者注入及恢复故障 |
| HOLMES_API_KEY | 调查服务访问 Holmes 私有 API |
| OPENAI_API_KEY | 可选模型服务凭据，由操作者在本地填写 |
| AGENT_MODE | offline 或 holmes |
| APP_PORT / CHECKOUT_PORT | 本地端口，默认 18760 / 18761 |

变更宿主端口时，给演示命令传入 --app 和 --checkout 对应地址。真实 .env、数据库、缓存都由 Git 忽略。Holmes 配置固定为官方 https://api.openai.com/v1 和 gpt-6.1-sol，使用 Responses API 调用工具，最多 4 个调查步骤。只在确认模型和费用后将 AGENT_MODE 设为 holmes、配置相应凭据，并运行 docker compose --profile holmes up -d --wait。启用后发起调查可能产生费用。模型不可用时任务会记录失败。

## 本地开发和验收

需要 JDK 21、Maven 3.9、Python 3.11+、Node.js 22 及 Chromium。在仓库根目录执行：

~~~sh
python -m pip install -r agent/requirements.txt
npm --prefix frontend ci
cd frontend
npx playwright install chromium
npm test
cd ..
mvn -f backend/pom.xml verify
python -m unittest discover -s agent -p "test*.py"
python scripts/verify_local.py
~~~

如要复用本机 Edge / Chromium，可设置 CAUSELUME_BROWSER 为浏览器可执行文件的绝对路径。verify_local.py 使用已打包的 JAR、空内存数据库、临时凭据和回环端口启动三个进程，退出时停止这些进程。部署用的持久化数据库与开发验收隔离。

阶段范围、来源版本和复用决策见 [实施记录](docs/implementation.md)。本项目面向单实例本机演示，真实生产数据源需要单独登记并配置只读工具。
