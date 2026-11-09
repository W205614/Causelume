# 第三方组件与素材

项目的 MIT 许可证适用于维护者拥有版权的代码和自制素材。下列依赖、工具和独立服务保留原有版权与许可，不因本项目采用 MIT 而变更。

## 源码发布范围

源码 Release 发布 Git 跟踪的源码、配置示例、文档和四张使用内置演示数据的控制台截图。源码中没有打包 Maven/PyPI/npm 依赖、模型权重、第三方服务镜像、浏览器、字体或图库素材。前端使用系统字体和项目自有样式；截图不包含真实账户或业务数据。维护者已确认有权以 MIT 发布自有代码和素材。

参考上游 API 文档与直接复制上游实现的许可责任不同。本次检查未发现另行复制且缺少声明的第三方实现；若后续直接复用代码或素材，应在本文件记录原始来源、版本、修改与许可证，并随分发保留要求的版权和许可文本。

## Java 运行依赖

本次按 Maven POM（包括继承的许可证）及构建 JAR 中的 71 个嵌套依赖核对。下表按同源组件合并；精确解析版本可运行 `mvn -f backend/pom.xml dependency:tree -Dscope=runtime` 查看。依赖原 JAR 的 LICENSE/NOTICE 保留在嵌套 JAR 中，构建没有移除这些文件。

| 组件（本次解析版本） | 上游许可证与来源 |
|---|---|
| Spring Boot 4.1.2（含 jarmode-tools）、Spring Framework 7.0.10、Spring Security Crypto 7.1.2 | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/v4.1.2/LICENSE.txt) |
| Tomcat Embed 11.0.27、Log4j 2.25.5、Commons Codec 1.21.0、Commons Logging 1.3.6 | Apache-2.0；各依赖自带 LICENSE/NOTICE |
| Jackson Core/Databind 3.1.7、Annotations 2.21、SnakeYAML 2.6 | Apache-2.0 |
| Flyway Core/PostgreSQL 12.4.0、HikariCP 7.0.2 | Apache-2.0 |
| Micrometer 1.17.2、Prometheus Metrics 1.7.0、JSpecify 1.0.1 | Apache-2.0 |
| SLF4J 2.0.20、Checker Qual 3.55.1 | MIT |
| java-otp 1.0.0 | [MIT，Jon Chambers](https://github.com/jchambers/java-otp) |
| PostgreSQL JDBC 42.7.14 | BSD-2-Clause；依赖自带许可与第三方说明 |
| HdrHistogram 2.2.2 | CC0-1.0 或 BSD-2-Clause，按上游许可选择 |
| Logback Core/Classic 1.5.38 | EPL-2.0 或 LGPL-2.1-only，按上游双许可选择 |
| Jakarta Annotation 3.0.0 | EPL-2.0 或带 Classpath Exception 的 GPL-2.0，按上游双许可选择 |
| Jakarta Mail 2.1.5、Angus Mail 2.0.5 | EPL-2.0 / GPL-2.0 with Classpath Exception；另有 EDL-1.0 声明，依原包条款区分文件 |
| Jakarta Activation 2.1.4、Angus Activation 2.0.3 | EDL-1.0 |
| H2 2.4.240 | [MPL-2.0 或 EPL-1.0](https://github.com/h2database/h2database/blob/version-2.4.240/LICENSE.txt)，按上游双许可选择 |

这些库通过 Maven 引用，未修改其源代码。镜像保留嵌套 JAR 的原许可与 NOTICE；本项目分发 Logback 时选择 EPL-2.0、H2 选择 MPL-2.0、Jakarta Annotation/Mail 与 Angus Mail 选择 EPL-2.0，原包中按文件适用的 EDL 声明继续保留。对应版本源 JAR 随镜像版本的第三方源码附件提供。

## Python 与前端测试依赖

Agent 使用 Flask 3.1.3（BSD-3-Clause）及 waitress 3.0.2（ZPL-2.1）。Flask 的安装依赖 Werkzeug、Jinja2、MarkupSafe、itsdangerous、Click、Blinker（BSD 系列或 MIT）；以安装版本的 `.dist-info` 中许可证为准。锁定的直接依赖见 [requirements.txt](agent/requirements.txt)。

前端运行时不依赖 npm 包。开发测试使用 Playwright 1.65.0（Apache-2.0），版本见 [package-lock.json](frontend/package-lock.json)；其 LICENSE、NOTICE 与 ThirdPartyNotices 随 npm 包保留。浏览器由执行者安装，不包含在源码附件中。

## 独立运行的服务和构建工具

Compose 引用的独立上游服务镜像不包含在源码附件中，也不由本项目重新发布。应用 app 与 agent 镜像的分发材料见下一节。

| 组件 | 上游许可 |
|---|---|
| [HolmesGPT 0.46.0](https://github.com/HolmesGPT/holmesgpt/blob/0.46.0/LICENSE) | Apache-2.0；镜像内部依赖另有自己的许可 |
| [Prometheus](https://github.com/prometheus/prometheus/blob/main/LICENSE)、[Alertmanager](https://github.com/prometheus/alertmanager/blob/main/LICENSE)、[Alloy](https://github.com/grafana/alloy/blob/main/LICENSE) | Apache-2.0 |
| [Loki](https://github.com/grafana/loki/blob/main/LICENSING.md) | 默认 AGPL-3.0-only，部分文件例外见上游 LICENSING.md |
| [Caddy](https://github.com/caddyserver/caddy/blob/master/LICENSE)、[Trivy](https://github.com/aquasecurity/trivy/blob/main/LICENSE) | Apache-2.0 |
| [Mailpit](https://github.com/axllent/mailpit/blob/develop/LICENSE) | MIT |
| [PostgreSQL](https://www.postgresql.org/about/licence/) | PostgreSQL License |
| [restic](https://github.com/restic/restic/blob/master/LICENSE) | BSD-2-Clause |
| Python、Maven、Eclipse Temurin、Alpine/Ubuntu 基础镜像 | 按各发行版、运行时及所含软件包许可证使用 |

对这些独立组件进行修改、重新打包或转售时，应重新核对对应版本的源码与分发义务，尤其是 Loki 的 AGPL 条款。本文件记录当前依赖与发布边界，不将独立服务重新授权为 MIT。

## 应用镜像分发

app 基于固定摘要的 Eclipse Temurin 21 JRE / Ubuntu Jammy，agent 基于固定摘要的 CPython 3.13 / Alpine 3.24。镜像随附本项目 LICENSE、本说明及 [对应源码说明](deployment/licenses/SOURCES.md)，位于 `/usr/share/causelume/licenses/`；保留 Temurin legal、Ubuntu copyright、CPython LICENSE 和 Python dist-info 许可证。

镜像版本对应的 Release 同时提供 `causelume-v<版本>-third-party-sources.zip`，包括实际运行版本的 Ubuntu 原源码与发行版补丁、Alpine 精确 commit 的构建配方/补丁及校验后的上游源码、Temurin 和 CPython 源码及应用依赖源包。`manifest.json` 记录镜像 ID、实际组件版本、下载来源与文件摘要；Release 的 SHA256SUMS 校验附件。原始许可、版权与构建脚本随对应源包保留。该附件由 `scripts/collect_image_sources.py` 从最终候选镜像采集；精确源版本缺失或摘要不符会阻止镜像发布，不用新版本源码替代旧二进制对应源码。

Maven、pip 和前端 Playwright 仅用于构建/测试，不进入应用运行镜像。Holmes、PostgreSQL、Prometheus/Loki 等独立服务继续由 Compose 拉取原始上游镜像，其发行与许可不由本项目 MIT 重新授权。
