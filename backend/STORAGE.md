# 账号与 PostgreSQL 迁移

开发默认使用 H2；Spring profile `prod` 使用 PostgreSQL 18，所有登录账户必须绑定 TOTP。APP_DB_URL、APP_DB_USER、APP_DB_PASSWORD 供业务连接使用；Flyway 使用独立账号 `causelume_migrator` 和 APP_MIGRATOR_PASSWORD。新 PG 库从 V5 建表，两个数据库共用 V6 以后的迁移。已有 H2 保留原来 V1–V5 的校验和。生产 Spring 配置支持 `/run/secrets/` 下以属性名命名的文件。生产 Compose 先运行 `storage migrate-pg` 一次性任务，成功后才启动业务服务；业务服务关闭自动 Flyway 且不挂载迁移密码。

## 独立部署迁移

```sh
java -jar causelume-1.0.2.jar storage migrate-pg
```

该命令只接收 PostgreSQL APP_DB_URL 和 APP_MIGRATOR_PASSWORD（或对应的受限文件），不启动Web、worker、邮件或账户初始化。成功后撤销业务角色对flyway_schema_history的INSERT/UPDATE/DELETE，保留只读访问。业务表的DML权限仍然保留。非Compose部署也应先执行此命令，再以业务凭据及 SPRING_FLYWAY_ENABLED=false 启动应用；只向迁移进程提供迁移凭据。H2本地演示仍由启动时Flyway增量迁移。

## 账号

| 操作 | 请求与结果 |
|---|---|
| 登录 | POST /api/login 传 username、password。已绑定账号缺少 code 返回 401 以及 MFA_REQUIRED，提交原登录参数和六位动态码或一次性恢复码重试 |
| 身份 | 登录或 GET /api/me 返回 id、username、role、email、mfaEnabled、enrollmentRequired |
| 开始绑定 | POST /api/account/mfa/enroll 传 password。返回仅本次绑定使用的 secret 和 otpauth uri，需在同一会话确认 |
| 确认绑定 | POST /api/account/mfa/confirm 传 code，成功后一次性展示八个 recoveryCodes，请离线保管 |
| 修改资料 | POST /api/account/profile 传 email，空字符串清空，返回更新后的身份 |
| 修改密码 | POST /api/account/password 传 currentPassword、newPassword，成功后重新登录 |
| 管理员操作 | POST /api/users 支持可选 email。POST /api/users/{id}/password 传 newPassword。POST /api/users/{id}/status 传 active。至少保留一个启用的管理员 |

生产首次验证密码后获得的会话只能查看身份、退出或绑定 MFA。稳定的 APP_MFA_KEY 为 32 个随机字节的标准 Base64 编码，加密已绑定的 TOTP seed；恢复数据库时应同时安全恢复这把钥匙。开发环境只有配置这把钥匙后才可主动绑定。登录采用 30 秒周期、六位 TOTP，接受相邻一个时间窗，已消费的窗口不得重放。单个恢复码含 128 位随机熵，只保存 SHA-256 摘要。绑定、改密、重置及停用会更改 authVersion。请求入口逐次检查版本与启用状态。

管理员遗失设备和恢复码时，获授权的数据库运维人员可从本机终端执行以下命令。CLI 交互读取新密码，也可通过仅本次使用的 APP_RECOVERY_PASSWORD_FILE 注入。它限制目标角色为 ADMIN，更换密码、清除 MFA 和恢复码、恢复启用并撤销会话，写入 RECOVER_ADMIN 审计。账户再次登录后需要重新绑定。

```sh
java -jar causelume-1.0.2.jar storage recover-admin admin
```

## 从 H2 冷备迁移

1. 正常停止旧实例后取得完整冷备副本，并对副本做导出。导出器通过只读 JDBC 打开已有的库，默认源用户名及密码均为空。非默认值通过 APP_SOURCE_DB_USER、APP_SOURCE_DB_PASSWORD 或对应的 _FILE 配置。
2. 在权限受限、位于 Git 工作区之外的目录保存快照。文件包含密码哈希、历史数据，已绑定的库还包含加密的 MFA seed 和恢复码摘要。保护访问权限与备份介质。导出文件是便于验证的数据格式，需由运维人员自行加密保存及传输。
3. 准备专用空 PG 数据库。迁移账号需拥有数据库 CREATE、public schema 的 CREATE 权限和对其创建的对象授权的能力。应用账号只需业务表 DML、序列使用权。向 CLI 提供上述数据库连接和角色参数。CLI 凭据按环境值、该变量的 _FILE、/run/secrets/ 同名文件依次读取。

```sh
java -jar causelume-1.0.2.jar storage export-h2 /secure/cold-copy/causelume /secure/export/causelume.jsonl
java -jar causelume-1.0.2.jar storage import-pg /secure/export/causelume.jsonl
```

导出接受 Flyway V5 或更新的 H2；基路径不含 .mv.db 后缀。导出遍历业务表和外键，按主键输出 UTF-8 JSONL，附每表行数、SHA-256 和 identity 的下一个值。它拒绝覆盖同名文件。导出中断时使用新的输出路径；缺少完整结束记录的文件无法导入。

导入在随机命名的临时 schema 安装与来源相同版本的表，分批写行，再从目标库重读核对行数、内容和序列；随后在临时 schema 执行余下 Flyway 升级。在空目标上通过单次事务移入 public。发现非空目标、版本不支持、列不一致或校验失败时拒绝发布。导入失败会清理本次临时 schema，原始冷备仍可用于回退。成功后检查旧工单、告警、账号、审计和调查结果，确认新工单 ID 继续递增，再切换流量。迁移不会将旧会话带入新实例。

`python scripts/verify_postgres.py` 会创建独立临时 PG18 容器，使用合成账号与旧 V5 样本验证完整性、最小数据库权限、MFA、防重放和幂等性。可用 `--maven <path>` 指向 Maven。TOTP 使用 MIT 授权的 [java-otp 1.0.0](https://github.com/jchambers/java-otp)，数据库兼容层使用 [Flyway PostgreSQL 模块和 JDBC 驱动](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)。
