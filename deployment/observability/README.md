# 观测接入与验收

生产组合使用 `compose.production.yaml` 与 `compose.observability.yaml`。应用、样例、采集器和告警组件无宿主观测端口。生产环境下 Python 位于业务/查询网络，只能经 `query-gateway` 的白名单 GET 路由读指标和日志。Loki push 与 Prometheus 管理接口留在采集网络。外部实例应在网关或供应商侧配置等价只读授权，服务端注册文件可引用专用 secret file。

## 注册一个服务

1. 确认 Prometheus 目标持有相同的 `service`、`environment` 标签。对 Micrometer 应用抓取 `/actuator/prometheus`。本项目使用 `app:9091` 与独立的 `MANAGEMENT_TOKEN`，示例 `example-service:8085` 仅在内部网络提供采集。
2. 确认 Alloy 正在读取正确的项目日志。配置只读取项目共享卷内的 `app.log`、`example.log`，并向 Loki 发送相同的两个标签。读取位置保存在独立卷中，容器不挂载 Docker socket。单条 JSON 日志可以提供 `event,level,status,duration_ms`；其他内容在 Python 的证据边界脱敏。[Alloy 文件采集文档](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
3. 在 Java 管理界面将被监控服务绑定至 `prom-loki`，填入两个标签，执行连接检测。默认目标为 `causelume/local` 与 `example-service/local`。检测成功后应实际发起一个覆盖业务请求的历史窗口调查，查看两个来源、查询语句、时间、样本与覆盖程度。Prometheus 抓取间隔为 10 秒，窗口内只有一次抓取时计数器增量可能尚不可用。
4. `service-alerts.yml` 将连续抓取失败或 5xx 增量发送到 Alertmanager。真实 webhook 以 `/run/secrets/ALERTMANAGER_TOKEN` 认证，firing 与 resolved 保持相同指纹。要接收平台自身的业务告警，应在服务目录登记 `serviceLabel=causelume`、`environment=local` 并设置负责人。自定义标签时以 Prometheus 的实际标签为准。整台平台宕机时需要机外探测并通过独立通道通知；后续恢复验证和工单关闭由人工执行。

Prometheus TSDB 设置 7 天及约 2 GB 上限。Loki 使用单实例 TSDB、文件系统存储与 7 天保留期，清理异步运行，磁盘容量另行监控。Loki 官方镜像不带 shell，探活使用网络内 `GET /ready` 与 Prometheus 的 `up` 采样。[Loki 保留机制](https://grafana.com/docs/loki/latest/operations/storage/retention/)

## 接入同一 Docker 主机上的另一个 Compose 项目

以业务项目中的 `orders-api` 为例。先在 Docker 主机运行 `docker network create --internal orders-metrics` 建立专用采集网络（已有该网络时复用）。将下面的字段合并到业务项目 Compose 文件，保留该服务已有的业务网络。业务 Compose 启动时创建日志卷。将 Spring 的管理端口配置为 9091，并启用 Prometheus 端点。日志卷需要允许业务进程写入、采集进程读取。

```yaml
services:
  orders-api:
    networks: [default, metrics]
    volumes: [orders-logs:/var/log/orders]
    environment:
      LOGGING_FILE_NAME: /var/log/orders/app.log
networks:
  metrics: {name: orders-metrics, external: true}
volumes:
  orders-logs: {name: orders-logs}
```

在业务项目启动后，将 `OBSERVED_METRICS_NETWORK=orders-metrics` 与 `OBSERVED_LOG_VOLUME=orders-logs` 写入平台的 `production.env`。以仓库根为工作目录，复制示例为主机专属配置，再检查组合能否解析：

```console
cp deployment/observability/compose.external-project.example.yaml deployment/observability/compose.site.yaml
docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml -f deployment/observability/compose.site.yaml config -q
```

`scripts/release.sh` 自动加载这个固定路径的主机配置，仅给 Prometheus 加入专用采集网络，并给 Alloy 只读挂载这一个日志卷。手工管理或回滚时也要使用同一组 Compose 文件。主机配置随 deployment 目录归档。

在部署的 `deployment/observability/prometheus.yml` 的 `scrape_configs` 列表加入以下 job。若目标端点要求认证，为 Prometheus 单独挂载业务项目提供的只读凭据，并在 job 的 `authorization.credentials_file` 中引用容器内路径。需要抓取失败告警时，把 `orders` 加入 `service-alerts.yml` 中 `ServiceScrapeUnavailable` 的 job 过滤器。

```yaml
  - job_name: orders
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["orders-api:9091"]
        labels: {service: orders, environment: prod}
```

在 `deployment/observability/config.alloy` 的 `path_targets` 列表加入：

```alloy
{ "__path__" = "/var/log/observed-project/app.log", "service" = "orders", "environment" = "prod" },
```

应用 Compose 网络、卷和配置变更后，重建采集容器以加载新配置。触发一次业务请求，确认应用写出日志。在平台服务目录绑定 `prom-loki`、`orders`、`prod` 并执行检测。显示名可以使用业务名称；Alertmanager 按采集标签与环境匹配。旧告警省略环境时必须只有一个服务匹配，多个候选会返回 HTTP 409。

## 隔离验收

从仓库根目录运行。Python 服务测试使用 `agent/requirements.txt`。Docker 必须能够取得固定镜像，示例 Java 服务构建需要 Maven 构件缓存或 Maven Central。

```console
python -m unittest discover -s agent -v
python agent/verify_observability.py
python agent/verify_alertmanager.py
```

第一个 Docker 验收创建独立项目并构建 Spring Boot 示例，使用在回环地址随机分配的测试端口读取真实 Prometheus 与 Loki。六类场景和事实判据在 `agent/evaluation-cases.json`。程序逐项检查不可抓取、5xx、慢请求、依赖拒绝连接、无数据、日志注入。采集使用实际 Micrometer、Prometheus、Alloy、Loki，分析使用离线规则。

第二个 Docker 验收用生产 Alertmanager 配置连接隔离网络内的捕获器，核对真实 HTTP 请求的 Bearer、路径、状态和指纹。它覆盖告警投递协议；Java webhook 的鉴权、幂等与派工另外由后端测试验证。两个脚本都使用独立随机项目名，只回收各自的临时容器、卷与网络。六场景验收的就绪等待最多 100 秒，每个样本的观测轮询最多 25 秒，慢请求、网络读取和构建另计。CI 建议为首次镜像/依赖下载预留独立任务超时；Alertmanager 黑盒运行上限为 180 秒。
