# 单实例小团队加固 · 2026-11-06

基线cb868aa。目标由用户确认为企业内部小团队、单实例优先，继续保持Java业务权威、JDBC、单worker、人工发起只读调查。公网资源尚未接入；后续单次授权的真实模型复验见文末。

## 已落实与验证

| 场景 | 修正 | 已执行的证据 |
|---|---|---|
| 已登录账户反复猜当前密码 | 账户级共享限流、审计，跨会话及密码/MFA入口共同计数 | 真实HTTP/H2请求验证限额和恢复、旧邮箱刷新 |
| 权限撤销撞上已放行管理员写入 | 按管理员ID顺序加锁，事务内复核authVersion与角色 | 固定撤销时序验证创建账号/重置密码拒绝 |
| 运行进程持迁移凭据 | 独立migrate-pg任务；业务只持DML凭据；禁止写Flyway历史 | 真实PG18的业务写入成功，DDL/history写入拒绝；恢复后同样拒绝 |
| 通知分页/小数预算/409复核 | 保留投递scope，支持小数美元，刷新最新版本后由用户再确认 | 浏览器覆盖分页、金额、服务编辑、工单冲突及权限按钮 |
| 会话失效仍保留绑定材料 | 清除MFA种子/恢复码及旧表单，MFA_REQUIRED保留该次合法登录流程 | 生产profile下真实绑定及恢复码再次登录 |
| 严格CSP阻止状态图 | 通过自有脚本CSSOM设置比例 | 同生产CSP响应头下实际像素3:1，零CSP违规 |
| 内部请求走环境代理 | 受控HTTP直连，避免代理收到内部凭据和快照 | 网络隔离容器内代理捕获数0 |
| 来源返回错归属/checkout错误含任意内容 | 校验服务/环境与Prom评价时点，未知错误采用现有受控分类 | 错归属仅对应来源partial；模型替身和结果均无注入秘密 |
| 手工和定时备份重叠 | flock互斥、失败退出清理、发布先重建维护镜像 | 真实共享Docker卷上的锁竞争/释放及完整加密恢复 |
| 老业务写入撞上部署迁移 | 停app后备份，成功后独立迁移再启动 | 发布顺序、备份失败不迁移及首次部署分支检查 |

本地针对性测试：Java默认17项、PG专项2项、前端16项、Agent16项，真实观测六场景及生产候选六组闭环通过。各提交的CI、全量复核和实际升级结果记录在后续部署段及对应工作流中。

## CI后全量复核

业务提交3195fbf的七项CI通过：[Verify](https://github.com/W205614/Causelume/actions/runs/42205164800)。同提交镜像扫描和[发布工作流](https://github.com/W205614/Causelume/actions/runs/42207012833)通过，日常镜像带该提交的revision标签。

CI后重新运行：Java17项、PG专项2项、前端16项、Agent16项、发布脚本5项、真实加密备份恢复、候选六组、观测六场景、真实Alertmanager发生/恢复、官方Holmes断网工具黑盒及回环Responses均通过。普通Docker栈与host Java/Python/浏览器栈分别完成HTTP503→200、持久化证据/复盘及会话/MFA验证。所有这些流程使用独立合成数据，无付费模型。

候选冷启动复核中两次原生socket TimeoutError提前逃出探针；修正为纳入已有90秒等待，host启动探针同样沿用原60秒期限。真实慢HTTP监听器的回归通过。官方Holmes首次并行启动未在原50秒内就绪；同配置、原时限串行复跑通过。

四张README效果图已从显式演示入口重新截取：1440px概览/工单/调查，390px手机调查；逐张检查了折行与溢出。截图使用内置样例数据。

## 适用场景与后续接入

当前采用单实例共享工作区，成员共享查看工单与证据。租户隔离、SSO、多实例协调和自动生产修复属于后续扩展项。接入外部资源后检查SMTP送达、公开TLS、机外告警、异地RPO/RTO和实际容量；平台预留用于调查准入，实际费用按供应商账单核对。上述加固验收采用离线模式，后续单次付费复验记录在文末。

## 文件与数据保留

旧Holmes挂载已经解除，随后将agent/holmes.yaml、agent/holmes_read.py、agent/holmes-snapshot.yaml移入回收站。当前Compose、官方镜像黑盒及调查接口均使用holmes-observations.yaml；旧实现由Git历史及升级前恢复集保留，更新部署使用新配置路径。

另外移入回收站的目录：两份早期pre-v3备份、一份本次ACL失败后留下的空备份目录、验证成功后的演练克隆，以及agent/__pycache__、scripts/__pycache__、frontend/test-results三个生成目录。升级前/后两份有效恢复集保留，回收站未清空。

演示入口、四张新截图、锁文件、数据库历史迁移、协议测试及有唯一证据的历史文档继续保留。Docker清理只针对本项目，不执行全局prune。10个旧版或测试镜像逐个核实所有运行及停止容器均无引用、没有其他项目标签后移除；当前app/agent/checkout镜像及其revision标签、固定Holmes镜像保留。更新时替换4个旧容器和3个旧网络，最终保留4个新版停止容器及其3个网络，未发现需删除的额外项目卷，causelume_business业务卷保留。未操作其他项目容器、镜像、数据卷、网络及共享构建缓存。

## 日常部署与数据核验

日常部署使用本机回环HTTP、H2与checkout示例；Linux/PostgreSQL链路在隔离环境验证。云主机、域名和服务商SMTP列入后续接入项。

1. 停止本项目Java服务后，冷备业务卷、旧server.jar及恢复所需配置到当前用户目录Causelume-backups。目录仅当前Windows用户及SYSTEM可访问，配置及导出不进入Git。Windows初始化脚本改用SID设置ACL，避免非ASCII账户名解码后授权失败；本机实际初始化及重复执行保留原值的检查通过。
2. 在断网的独立数据副本上运行新镜像，由Flyway V5升级至V9，核对全部既有字段及行。正式升级后再次核对：accounts 2、audit 20、services 1、alerts 2、task_admission 1、tickets 2、tasks 2、timeline 12全部一致，原调查JSON和密码哈希未改写。升级前没有PENDING/RUNNING调查。
3. 保留20261106-192032-pre-team-3195fbf与20261106-193341-post-team-3195fbf两个私有恢复集。分别复核清单8/6个文件的SHA256；升级后冷备数据库文件与停止状态下业务卷的SHA256相同。升级前恢复集另保留核对导出，升级后恢复集保留新JAR及当前配置；这些文件可能含凭据和业务信息，不对外分享。
4. 日常app/agent/checkout使用本机构建的3195fbf镜像，Holmes继续使用固定0.46.0摘要。四服务实际启动并达到healthy。app的77项应用类和资源与当前本地构建逐字节相同，包含当前前端文件；agent四个源码文件和Holmes配置也与工作区相同。实际JAR包含Spring Boot 4.1.2、Tomcat 11.0.27及Jackson 3.1.7。
5. 原.env未改写，另用不入库的compose.override.yaml将app和agent固定为offline。本次日常部署核验未新增调查、未使用现存模型密钥。验证后执行项目stop，app、agent、checkout、holmes均已停止。后续可用docker compose --profile holmes up -d启动；该命令自动合并本机override，改回真实模型模式前需显式调整覆盖配置、预算与授权。

1a71303的[七项CI](https://github.com/W205614/Causelume/actions/runs/42209190263)已通过。后续收尾提交仅包含Windows配置工具修正、旧挂载文件移除及文档，不改变已核验的业务镜像内容；以该提交对应Verify工作流确认最终仓库状态。

本机操作的哈希清单、迁移核对、镜像归属及清理结果存于Codex本次任务的team-maintenance私有审计目录。外部接入步骤见上方“适用场景与后续接入”及production-setup.md。

## 后续单次真实模型复验

2026-11-06（Asia/Shanghai），用户授权一次真实模型调查费用。以91ebd43为业务基线，加上本次显式超时/重试配置，在独立随机名的测试栈运行，复用现有OPENAI_API_KEY及官方gpt-6.1-sol配置。数据来自独立测试库、合成账户与示例日志，密钥通过运行时环境传入测试Holmes进程。

| 环节 | 实际结果 |
|---|---|
| 初始化 | 独立PostgreSQL18、真实Spring示例、Prometheus、Loki、Alloy、Alertmanager、Mailpit及固定Holmes0.46.0启动；真实MFA绑定与服务数据源连接通过 |
| 故障接入 | 实际POST示例故障接口取得503；Alertmanager Webhook自动生成并分派1张工单；站内通知及Mailpit收件通过 |
| 付费调查 | 只创建1个调查任务；mode=holmes、modelExecution=called、status=SUCCEEDED；frozen_metrics和frozen_logs均返回本次冻结视图，结构化分析和证据持久化 |
| 采样证据 | snapshotId=b76c4a534dd1407fabafd88620bc3172。Prometheus实际错误增量大于零，Loki实际包含request_failed；日志按既定上限截断，快照标记partial，模型明确说明缺失与截断，并将可能原因与已观测事实分别呈现 |
| 模型用量与费用 | 上游汇总输入8,782、输出754 token；estimatedCostUsd和costUsd均未知。独立环境设置每日/单次预留各1美元用于准入，最终保留UNKNOWN状态；供应商实际费用待账单核对 |
| 恢复闭环 | 实际健康请求取得200；Alertmanager恢复事件写入时间线，工单仍由人确认；随后VERIFY、结构化复盘、CLOSE和授权JSON导出通过 |
| 浏览器与恢复 | 真实浏览器用恢复码登录，查看模型分析、两项工具证据和反馈；刷新后仍可读；关闭工单与处理时间线可读。另实测独立测试账号的本机CLI恢复及重新绑定MFA |
| 隔离与收尾 | 日常业务卷及原.env、compose.override.yaml哈希未变。测试容器、网络、日志卷和本次专用示例镜像已停止并移除；原业务数据及有效恢复集保留 |

首次启动因本机Docker默认地址池耗尽而失败，当时尚未创建调查或调用模型。核对36个Docker网络与38条本机路由后，为本次测试选择不冲突的独立子网，其他项目保持原状。模型闭环已完成后的首次浏览器读回因Windows临时验收脚本把中文以GBK传给Node而失败，日志读取也暴露相同编码问题。修正为UTF-8后继续读回同一数据库、同一调查，付费调查总数保持1次。

本次检查发现Holmes默认单请求超时600秒、部分上游错误隐式重试等待30秒，与平台80秒等待不一致。本地及生产编排现显式设置LLM_REQUEST_TIMEOUT=60、LLM_RATE_LIMIT_MAX_WAIT_SECONDS=0。无费用回归使用固定官方镜像及真实回环Responses协议，确认503只产生1个请求并明确失败，既有冻结工具及注入阻断检查同样通过。单请求限时不是四步调查的总时限；调查总等待仍可能超期，结果不明时保留额度，不自动重发。

日常Holmes容器已按新环境变量重建，实际启动达到healthy后停止；另外三个日常容器始终保持停止。本次健康检查没有提交模型请求。应用和Agent业务镜像内容未改变，无需重新构建；原本机offline覆盖配置保留。代码修正和协议回归对应[2c17bee的Verify](https://github.com/W205614/Causelume/actions/runs/42216549850)，后续文档提交只补记此项健康验证。

这次验证的场景为一个受控503故障，邮件终点为本地Mailpit。公网TLS、机外探测、服务商SMTP、异地恢复目标和长期容量按生产接入手册检查。私有real-model-flow审计目录保存授权提交标记、调查与工单导出、测试库dump及日志；CI使用离线模式和模型协议替身。
