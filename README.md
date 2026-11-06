# Causelume · 故障诊断与协作平台

值班人员从告警或人工报障创建工单，分派负责人，结合指标和日志调查，再记录处理、恢复验证与复盘。适用于单实例运维流程演示、开发与集成验证。

- **体验界面**　打开部署后的 `/?demo=1`。页面标记「模拟数据」，可以试派工和调查；刷新会重置样例。
- **验证完整流程**　使用默认的离线模式。示例 checkout 会真正返回 503、生成日志与指标，Python 使用规则分析，全程无需模型密钥。
- **接入模型**　HolmesGPT 的两个只读工具复查该次调查冻结的证据。模型给出推断和不确定性，由负责人核对。真实调用会产生供应商费用。

## 控制台实景

以下均为显式演示模式（`/?demo=1`）下的真实浏览器截图，使用内置样例，不含实际账户凭据或生产日志。

![运维概览：未关闭工单、近期告警和调查队列](docs/images/overview.png)

<details>
<summary>查看工单时间线、调查证据和手机布局</summary>

![工单详情及负责人处理时间线](docs/images/ticket-timeline.png)

![并排核对现场证据、诊断推断和反馈](docs/images/investigation.png)

[查看 390 像素宽的调查详情](docs/images/investigation-mobile.png)

</details>

## 首次部署

准备 Git、Docker Desktop（Linux 容器）、Docker Compose v2 和 Python 3.11+。容器构建需要访问镜像仓库及软件包仓库。

~~~sh
git clone https://github.com/W205614/Causelume.git
cd Causelume
python scripts/init_env.py
docker compose up -d --build --wait
docker compose ps
~~~

打开 [运维控制台](http://127.0.0.1:18760)，用 admin 或 operator 登录。初始密码分别取本机 .env 内的 APP_ADMIN_PASSWORD、APP_OPERATOR_PASSWORD。初始化脚本生成独立随机凭据；.env 已存在时会停止。默认只向本机回环地址发布控制台和 [演示 checkout](http://127.0.0.1:18761/health)。

## 完成一条调查

1. 在告警中心录入告警并建单，或在工单列表直接创建。按服务、负责人或状态找到目标工单。
2. 管理员分派给值班人员，负责人开始处理。工单详情展示处理记录、关联调查和当前可执行操作。
3. 在 checkout 上有请求样本后发起调查。查看观察来源、快照时间、可能原因、不确定性与建议，提交核对反馈。
4. 由负责人处理故障，在工单写入恢复验证依据，再关闭并记录复盘。其他人已更新同一工单时，先复核最新状态，再决定是否提交原草稿。

要在本地测试库中重放整条流程，运行：

~~~sh
python scripts/e2e.py --env-file .env
~~~

这条命令要求运行中的部署使用 offline。脚本以测试操作者身份注入 checkout 故障、实测 503、派工、调查、恢复并实测 200，最后写入已关闭的工单、证据和审计记录。请在允许写入演示记录的环境中串行执行。独立前端模拟页只改内存，适合纯界面体验。

## 架构和配置

| 组件 | 职责与边界 |
|---|---|
| 浏览器控制台 | 原生 ES modules，同源会话。分页查询工单、告警和任务；进入详情后加载证据。进行中的调查可自动或手动刷新。 |
| Java 21 / Spring Boot | 认证授权、服务目录、重复告警、工单幂等与乐观锁、派工、调查队列、时间线与审计。Spring JDBC 和 Flyway 管理 H2 文件数据库。 |
| Python 调查服务 | 在一个采样窗口冻结 checkout 观察数据。offline 使用规则，holmes 交给固定版本的 HolmesGPT；服务端校验并保存结果。 |
| checkout | 独立示例服务。用容量为 1 的许可池复现失败，保留最近 50 条请求。故障控制使用独立凭据。 |

| 环境变量 | 填写与用途 |
|---|---|
| APP_ADMIN_PASSWORD、APP_OPERATOR_PASSWORD | 首次建库时创建账号。已初始化的账号从命名卷读取。 |
| AGENT_TOKEN、EVIDENCE_TOKEN、DEMO_CONTROL_TOKEN | 分别授权发起调查、读取证据和操作**演示服务**。由初始化脚本生成。 |
| AGENT_MODE、HOLMES_API_KEY | 默认 offline；切换 holmes 时，使用独立的 Holmes 内部 API 凭据。 |
| OPENAI_API_KEY | 仅真实模型模式需要。官方模型地址为 `https://api.openai.com/v1`，模型为 `gpt-6.1-sol`。 |
| APP_PORT、CHECKOUT_PORT | 本机端口，默认 18760 和 18761；更改后用 --app、--checkout 指定验收地址。 |
| APP_PUBLIC_ORIGIN | 本地 HTTP 留空。由受控 HTTPS 代理提供服务时填写唯一公开 origin，例如 `https://ops.example.com`。 |

真实模型模式需要自行在服务端 .env 配置 OPENAI_API_KEY、AGENT_MODE=holmes 和 COMPOSE_PROFILES=holmes，然后执行 `docker compose up -d --build --wait`。确认数据发送范围及费用后，再从工单发起调查。手工使用脚本验证付费调查需显式传入 `--expect-mode holmes`。CI 使用离线模式。

## 已有部署与升级

日常停止和恢复使用 `docker compose stop`、`docker compose start`。业务库位于项目名对应的 business 命名卷，默认名为 causelume_business。查看进程状态可运行 `docker compose ps`，数据库健康状态见 [/api/health](http://127.0.0.1:18760/api/health)。

升级会执行新的 Flyway 迁移并短暂中断服务。先核对当前 Git 提交、镜像、.env、实际卷和备份；安排维护窗口，停止写入，制作一致备份并在隔离副本上演练恢复。确认后再拉取新提交、构建、启动并复查历史工单。应用回滚须配合兼容的数据库状态。保留原 .env，本机的凭据和数据不进入仓库。[上线与维护边界](docs/operations.md)

## 开发与测试

需要 JDK 21、Maven 3.9、Python 3.11+、Node.js 22 和 Chromium。在仓库根目录运行：

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

可通过 CAUSELUME_BROWSER 指定本机 Edge 或 Chromium 可执行文件。verify_local.py 使用临时账户、回环端口、空内存库及真实浏览器进行联调。

## 常见问题

- **调查显示证据不足**　先对 checkout 发起一次业务请求，再检查服务目录里的指标与日志。其他服务需要先登记其只读工具。
- **工单提交返回 409**　比较最新状态、版本和草稿。只有复核后仍符合状态规则的操作才能再次提交。
- **等待中的调查和运行中断有什么区别？**　单 worker 依次领取等待任务。已领取而结果未知的付费任务会记录失败原因；再次调查需由操作者决定。
- **改了 .env 密码仍不能登录**　初始密码用于首次建库；已有用户密码哈希保存在数据库。先确认使用了对应部署和账号。

[业务 API 与状态转换](backend/API.md) · [调查协议与快照](agent/README.md) · [前端与浏览器测试](frontend/README.md) · [参考与架构决策](docs/implementation.md) · [验收记录](docs/verification.md) · [本轮审查与截图来源](docs/review-2026-11-06.md) · [上线差距](docs/operations.md)
