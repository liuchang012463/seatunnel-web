# 离线导入原生客户端镜像与内网部署方案（ARM64 首期）

适用 SeaTunnel Web 1.0.0、SeaTunnel Engine 3.0.0。首期目标限定为 `linux/arm64`（aarch64）后端镜像，以及本开发环境的达梦 V8 和 Kingbase V008R006C009B0014 客户端。现存 x86 容器中的 Kingbase V009R001C010 不属于支持目标，不要求 ARM 客户端读取该版本生成的归档；amd64 客户端打包和其他数据库版本组合留待后续独立实现。数据库服务器的 CPU 架构不必与客户端一致。

## 交付范围与主线

- 主交付方式为：受控 ARM64 客户端 bundle 随构建输入提供，内置到独立的原生客户端后端镜像。厂商二进制、依赖库、凭据和验收数据不进入 Git；构建输入只来自明确指定的 bundle，不扫描或直接复制宿主机整个 `/opt`。
- Web 后端镜像只部署达梦和 Kingbase 的客户端工具及运行依赖，不安装或启动数据库服务端。若内网交付同时包含两个数据库服务，则作为独立的有状态服务部署，各自使用厂商 ARM64 服务端安装包、授权和持久化数据目录。
- 保留现有 `Dockerfile`、通用 `backend-runtime` 及其 amd64/arm64 发布流程。新增独立 `Dockerfile.native-arm64` 和 ARM64 原生客户端镜像构建入口，使用带 `native-arm64` 的独立标签；不得将只有 ARM 客户端的产物发布到通用多架构标签。
- 首期提供本地 ARM64 构建脚本，构建完成只产生本地镜像，不自动推送、部署或重启服务。厂商包未接入受控 CI 产物源前，不让现有公网 CI 自动构建原生客户端目标。
- 数据库服务可以部署在独立 ARM64 主机或独立容器；Web 容器与数据库容器分开管理。为简化内网安装，目标交付可将 Web、Kingbase、达梦的独立镜像和 root 安装脚本装进同一个离线包，一次导入并启动；不把三个服务合并成一个 JRE 应用镜像。开发/裸机运行 Web 时，主机安装受控客户端工具包并通过绝对路径配置即可。
- DMP 由原厂 `sys_restore`、`dimp` 还原；SQL 脚本仍走现有 JDBC/CopyManager。`ksql`、`disql` 为可选诊断工具，`sys_dump`、`dexp` 用于准备验收样本，不作为 SQL 导入依赖。

## Kingbase 与达梦的内网部署方式

“数据库也随本次内网交付”指三项独立部署：SeaTunnel Web ARM64 服务、Kingbase 服务端、达梦服务端。若目标环境需要新建这两套数据库服务，就分别交付并安装完整 ARM64 服务端；只安装客户端不能提供数据库监听和实例服务。服务端不是 Web 的依赖文件，不能塞进 Web 镜像一起启动；Web 通过配置的数据源地址连接两个数据库。若目标环境已有数据库服务，只部署 Web 镜像中的客户端即可，不要重复安装数据库实例。

| 部署对象 | 安装内容 | 建议方式 |
| --- | --- | --- |
| SeaTunnel Web | 应用运行时、`dimp`、`sys_restore`、各自 ARM64 动态库 | 使用本方案构建的 ARM64 Web 镜像。客户端只装工具及运行依赖，不包含 `dmserver`、Kingbase `Server`、数据库数据目录或数据库服务脚本 |
| Kingbase V8 服务 | 完整 KingbaseES ARM64 服务端组件、授权文件、实例及数据目录 | 从厂商受控渠道取得与内网 OS/ARM64 匹配的 V8 安装介质，在独立主机安装；只有厂商为该版本提供受支持的容器交付时才改用独立数据库容器。需要 Web 侧 `sys_restore` 时，只将客户端工具组件 `ClientTools` 及其依赖纳入 Web bundle |
| 达梦 V8 服务 | 完整达梦 ARM64 服务端组件、授权文件、实例及数据目录 | 从厂商受控渠道取得与内网 OS/ARM64 匹配的 DM8 安装介质，在独立主机安装；只有厂商为该版本提供受支持的容器交付时才改用独立数据库容器。Web bundle 选择达梦客户端安装组件，并确认其中包含 `dimp` 及其依赖 |

root 负责整个内网交付的安装编排：核对介质校验和、检查 OS/ARM64 兼容性、创建目录与服务账号、设置属主权限、安装所需系统依赖、按厂商流程注册开机服务、配置防火墙/网络，并部署 Web 镜像。root 是部署操作账号，不代表数据库安装器或数据库守护进程都以 root 身份运行。Kingbase Linux 安装手册要求以非 root 的 `kingbase` 用户执行安装，并将服务端、`ClientTools` 分列；达梦 Linux 安装流程也区分服务端/客户端组件，安装后再由 root 执行指定的系统配置脚本。两家数据库安装目录、数据目录和启动服务彼此隔离，数据库服务分别规划持久化数据盘、监听端口、账号/授权和备份策略，Web 仅配置网络可达的 JDBC 数据源。

### 是否将两套数据库服务端塞进 JRE 镜像

技术上可以做一个包含 JRE、Kingbase、达梦服务端的多进程镜像，但不建议把它作为交付目标。当前 Web 镜像基础是 `eclipse-temurin:21-jre-jammy`，它只为 Web 运行时和客户端依赖验证过；不能据此认定 Kingbase 与达梦服务端支持同一 Jammy/ARM64 运行环境。三个服务放在一个容器还会耦合启动、健康检查、升级、故障恢复和数据卷权限，且数据库文件必须持久化在镜像层之外。

Kingbase V8 有厂商 Docker 部署手册，示例包含初始化账号/密码参数和独立挂载数据目录；但仍需向厂商取得与目标版本、ARM64 和内网 OS 匹配的镜像或介质，通用 Docker 手册本身不能证明当前组合受支持。达梦安装介质同样必须与 CPU 架构和 OS 匹配，ARM64 安装包或 Docker 包需按具体版本取得并确认支持。当前仓库的 ARM64 bundle 只有 `dimp` 客户端，不能用来构建 DM 服务端镜像。[KingbaseES Docker 部署手册](https://help.kingbase.com.cn/v8/install-updata/install-docker/install-docker-1.html)、[KingbaseES 安装包说明](https://help.kingbase.com.cn/v8/intro/releasenotes-external/installation-package.html)、[达梦版本获取说明](https://eco.dameng.com/document/dm/zh-cn/start/dm-version-acquisition.html)

### root 执行的一键离线交付流程

为减少内网操作步骤，建议最终交付一个离线包，内部包含三个独立镜像和一个 root 安装入口：SeaTunnel Web ARM64 镜像（含导入客户端）、Kingbase V8 ARM64 服务端镜像、达梦 DM8 ARM64 服务端镜像，以及部署脚本、账号初始化 SQL/脚本、版本清单和许可证说明。镜像分别运行、数据目录分别持久化，Web 通过专用网络连接两家数据库。当前尚未取得并验证两家服务端的准确 ARM64 介质、授权和目标 OS 组合，因此服务端镜像与一键脚本仍是待实施项。

root 的安装入口负责校验离线包、导入镜像、创建数据目录、设置目录属主和容器参数，然后按依赖顺序启动 Kingbase、达梦和 Web，并检查健康状态与端口连通性。容器内数据库进程仍按厂商要求以 `kingbase`、`dmdba` 等账号运行；root 只负责宿主机安装和容器编排。

账号可以预先约定：容器操作系统账号使用 `kingbase`、`dmdba`，应用连接使用按导入需求授予权限的专用账号，不让 SeaTunnel 使用 `system`/`SYSDBA` 超级账号。安装时由 root 安装脚本提示输入或生成每套数据库的独立密码，并将凭据写入 root 专属凭据文件（权限 `0600`），再交给首次初始化流程；密码不得写入镜像层、Dockerfile、Git 或通用 `.env`。账号名称和初始化脚本可随离线包预置，密码应在目标环境首次部署时生成。

实施服务端镜像前，需先确认 Kingbase V8 的 ARM64 镜像/安装介质与目标 OS 支持矩阵，以及达梦 DM8 对应 ARM64 镜像或安装介质的来源和授权；如果达梦只提供适配 OS 的安装介质，则单独制作并验收 DM 服务端镜像，不能直接复用当前 JRE 镜像。不得把本机 x86 Kingbase V9 安装包或数据目录带入本方案。若目标环境已经有数据库，则跳过两个服务端镜像和初始化步骤，只加载 Web 镜像。

## ARM64 bundle 契约

bundle 至少包含以下内容；具体清单字段、命令行参数与实现一同补充，但必须可重复校验。内容限于客户端可执行工具及运行依赖，不含数据库服务端或实例数据：

```text
bundle/
  manifest.properties       # 格式版本、linux/arm64、两家客户端完整版本、来源标识
  SHA256SUMS                # 清单及全部实际交付文件的 SHA-256
  dm/
    bin/dimp
    bin/…                   # 厂商必需的同目录动态库与资源
    lib/…                   # 如该版本需要
  kingbase/
    bin/sys_restore
    lib/…                   # libkci 及经验证的间接依赖
```

实现使用固定的 bundle 根布局：除上述两个文件和 `dm/`、`kingbase/` 外不接受其他根条目。`manifest.properties` 必须包含且只包含以下字段；版本写完整具体版本，来源填写原厂包文件名及其 SHA-256 或内部受控制品标识，不得填写口令或带访问凭据的 URL：

```properties
format_version=1
platform=linux/arm64
architecture=aarch64
dm_version=<完整达梦客户端版本>
dm_source=<达梦原厂包名及 SHA-256 或受控制品 ID>
kingbase_version=<完整 Kingbase 客户端版本>
kingbase_source=<Kingbase 原厂包名及 SHA-256 或受控制品 ID>
```

`SHA256SUMS` 使用 GNU `sha256sum` 的常规格式，精确覆盖清单、所有常规文件和指向文件的链接，不包含 `SHA256SUMS` 自身。链接目标必须有效、相对且留在所属客户端目录内；指向目录的链接不纳入 bundle。路径不得包含反斜杠或换行符。

客户端和库须来自版本匹配、允许内部交付的原厂安装包或受控依赖包；保留执行权限及包内有效链接。构建前校验清单必填字段、校验和、必需工具和全部 ELF 文件的 ARM64 架构；必需工具须本身为完整 ELF64 AArch64 文件。校验器拒绝缺文件、校验和覆盖缺口或篡改、混入其他架构 ELF、越出 bundle 的符号链接。Kingbase V8 若缺 `libldap_r-2.4.so.2`，须随 bundle 提供来源和版本已验证的 ARM64 依赖包内容。

达梦客户端还有 `ldd` 无法展示的运行时动态加载依赖：`libdmdpi` 会通过 `dlopen` 查找未带版本号的 `libcrypto.so` 和 `libssl.so`。因此须在 `dm/bin` 或 `dm/lib` 中随达梦包提供匹配的 ARM64 OpenSSL 实库和包内相对链接，全部纳入 SHA256SUMS，并通过镜像构建中的 `dimp HELP=Y` 启动探针。不要整套复制宿主机 glibc、动态链接器或 `/usr/lib` 来补依赖。

当前后端基于 `eclipse-temurin:21-jre-jammy`。包的校验和只保证输入可追溯，不能证明 ABI 或数据库版本兼容；还须在最终 Jammy 镜像验证依赖闭包和工具启动。构建中不临时从不受控公网地址下载厂商包；发布记录保存基础镜像实际 digest/ID、bundle 校验和、客户端版本和源码提交。

## 子进程依赖隔离与运行配置

镜像内两家目录分别固定为 `/opt/native-clients/dm`、`/opt/native-clients/kingbase`；Java 配置直接指向 `/opt/native-clients/dm/bin/dimp` 和 `/opt/native-clients/kingbase/bin/sys_restore`。`DmpCommandBuilder` 为每个原生子进程单独设置 `LD_LIBRARY_PATH`，值为该工具自己的 `bin/:lib/`；该变量不进入 JVM 全局环境，也不混用两家库，不需要 wrapper 脚本。命令和进程仍由 Java `ProcessBuilder` 直接启动，参数、工作目录、Kingbase 临时 `KINGBASE_PASSWORD`、退出码和超时处理沿用现有行为。

构建期和裸机部署的客户端布局必须保持可推导：可执行文件在厂商 `bin/` 下，依赖库在同级 `bin/` 或其客户端根目录的 `lib/` 下。若厂商介质要求额外的库目录、资源目录或环境变量，应将已验证路径显式纳入该工具的子进程配置并补测试，不能改成 JVM 全局 `LD_LIBRARY_PATH`。

原生客户端镜像提供以下默认值，通用镜像继续保持工具路径留空；现有配置仍允许显式覆盖：

```text
SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/dm/bin/dimp
SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/kingbase/bin/sys_restore
SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import
SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240
SEATUNNEL_WEB_OFFLINE_IMPORT_SQL_STATEMENT_TIMEOUT_SECONDS=300
```

工作目录须能由实际应用运行用户写入。凭据仅由平台数据源在启动对应原厂子进程时传入；Kingbase 使用临时 `KINGBASE_PASSWORD`。凭据不写入清单、镜像层、Dockerfile 静态 `ENV` 或 build args，执行命令与工具日志沿用现有脱敏。

本机直接运行 Java 开发服务时，`.env.example` 中的两个客户端路径默认留空；将它们设为当前主机上的原厂二进制绝对路径，例如 `/opt/dmdbms/bin/dimp` 和 `/opt/Kingbase/ES/V8/ClientTools/bin/sys_restore`。子进程会按可执行文件的父目录及其上一级 `lib/` 自动设置库路径。`scripts/dev-up.sh` 会读取 `.env`，配置变更后用 `scripts/dev-restart.sh` 重启本地开发服务。使用 Compose/容器时改为镜像内的 `/opt/native-clients/...` 路径。

## 版本与归档兼容矩阵

“ARM64 客户端连接 x86 数据库成功”仅是连接证据，不能视作 DMP 还原通过。每个生产组合必须记录：客户端 CPU 架构、导出工具完整版本、源数据库完整版本与兼容模式、归档格式、还原工具完整版本、目标数据库完整版本与兼容模式，以及最终镜像中的还原结果。

| 候选组合 | 已知信息 | 首期发布要求 |
| --- | --- | --- |
| 达梦 ARM64 本地开发环境 | 本机 `/opt/dmdbms/bin` 有 `dimp`、`dexp` 及配套动态库；`dimp` 报告 V8，精确构建版本仍待采集 | 使用本机达梦库生成的单文件逻辑 DMP，完成实际预检与还原 |
| Kingbase ARM64 V008R006C009B0014 本地开发环境 | 客户端位于 `ClientTools/bin`、`ClientTools/lib`，含 OpenSSL 1.1、LDAP 2.4 依赖 | 使用本机 Kingbase 库生成的归档，验证现有 `--strict-names`、`--fromSchema`、`--targetSchema` 参数及实际还原 |

现存 x86 容器中的 Kingbase V009R001C010 及其归档明确在首期范围之外，不作为 ARM 客户端的兼容性或发布验收门槛。

首期只接受现有单文件上传流程能够处理的逻辑归档：Kingbase 验收基线使用 `sys_dump -Fc` 自定义格式（不能用默认 SQL 输出改名为 `.dmp`）；达梦使用 `dexp` 生成的单文件逻辑 DMP。目录备份、物理备份和分卷归档不属于本次新增范围。跨版本不自动推断兼容；未验证的组合保留为待验证，不能写成支持。

参考：[Kingbase sys_dump](https://help.kingbase.com.cn/v8/admin/reference/ref-client/sys_dump.html)、[Kingbase sys_restore](https://help.kingbase.com.cn/v8/admin/reference/ref-client/sys_restore.html)、[Kingbase Linux 安装指南](https://help.kingbase.com.cn/v8.6.8.20/PDF/%E5%9F%BA%E4%BA%8ELinux%E7%B3%BB%E7%BB%9F%E7%9A%84%E6%95%B0%E6%8D%AE%E5%BA%93%E8%BD%AF%E4%BB%B6%E5%AE%89%E8%A3%85%E6%8C%87%E5%8D%97.pdf)、[达梦 Linux 安装指南](https://eco.dameng.com/document/dm/zh-cn/start/dm-install-linux.html)、[达梦 DIMP/DEXP 手册](https://eco.dameng.com/document/dm/zh-cn/pm/dexp-dimp-function-introduction.html)。

## 实现与主线程验收

1. 实现 bundle 校验、按工具隔离的子进程库环境、ARM64 原生客户端镜像目标和本地构建入口，并补齐操作命令及必要测试；每个独立功能以中文 Conventional Commit 小步提交。
2. 构建在 ARM64 节点执行，目标显式固定为 `linux/arm64`。构建前验证输入；最终镜像中检查两套工具的 `ldd` 无缺失、版本/帮助能够通过直接调用运行、客户端文件和工作目录可由应用用户使用。错误架构、无效校验和、缺工具须在明确阶段失败。
3. 主线程独立审阅改动并验收：参数含空格/特殊字符仍原样传递，库环境不串用，凭据环境保留，退出码与超时终止行为保持；检查普通镜像构建及发布目标没有被改变。
4. 业务发布门槛是在最终 ARM64 后端镜像中用两家真实逻辑 DMP 完成预检与还原，核对对象、行数/代表性数据、Schema 映射，并验证失败归档、日志脱敏、超时终止及临时目录清理。仅 `ldd` 或版本命令成功不能替代业务还原验收。
5. 构建、临时容器检查和业务验收分别记录；厂商包、网络或数据库权限不足时明确列出未验证组合及原因。验收不得隐式部署、重启既有容器或操作 Compose。

## 本地构建命令与最小测试

在构建 ARM64 客户端 bundle 的安全工作目录中，完成厂商文件整理并生成校验和：

```bash
cd /受控路径/offline-import-arm64
find manifest.properties dm kingbase \( -type f -o -type l \) -print0 \
  | LC_ALL=C sort -z \
  | xargs -0 sha256sum > SHA256SUMS
```

构建机须有 ARM64 Docker daemon、已加载的 `eclipse-temurin:21-jre-jammy` 和唯一一个 `seatunnel-web-dist/target/seatunnel-web-*.tar.gz`。从仓库根目录执行：

```bash
python3 scripts/native-arm64/validate_bundle.py /受控路径/offline-import-arm64
scripts/build-backend-native-arm64.sh /受控路径/offline-import-arm64
```

默认只在本地生成 `seatunnel-web-api:1.0.0-native-arm64`。可传入第二个镜像标签，但标签必须包含 `native-arm64`。脚本先以未修改的仓库 `Dockerfile --target backend-runtime` 构建唯一临时本地后端 tag，再使用临时上下文中的独立 `Dockerfile.native-arm64` 从该 tag 构建最终镜像；两步都显式设置 `--platform linux/arm64`，构建完成会移除临时 tag。脚本校验 bundle 后构建，不推送、不部署，也不运行 Compose。镜像标签记录 bundle 校验清单摘要、本地 JRE 基础镜像 ID 和临时后端运行时镜像 ID；镜像内保留 manifest 与 SHA256SUMS 供追溯。

当前 `compose.yaml` 显式传入离线导入环境变量，会覆盖镜像里的 `ENV`。以后使用 Compose 切换到 native 标签时，也须在未跟踪的本机 `.env` 中显式设置工具路径和工作目录，例如：

```dotenv
SEATUNNEL_WEB_API_IMAGE=seatunnel-web-api:1.0.0-native-arm64
SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/dm/bin/dimp
SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/kingbase/bin/sys_restore
SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import
SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240
SEATUNNEL_WEB_OFFLINE_IMPORT_SQL_STATEMENT_TIMEOUT_SECONDS=300
```

该示例仅说明运行配置；本次交付不会修改或执行 Compose。

校验器的最小模拟测试覆盖清单覆盖（包括嵌套同名 `SHA256SUMS`）、内容篡改、完整 ELF64 头、必需工具 ELF 架构、x86 ELF 混入和越界链接：

```bash
python3 scripts/native-arm64/test_native_arm64.py
```

构建脚本会在最终 Jammy 镜像构建时运行两套工具的 `ldd` 缺库检查、带独立 `LD_LIBRARY_PATH` 的 `sys_restore --version` 和 DIMP 帮助启动探针；DIMP 探针同时验证 `ldd` 不会显示的 OpenSSL 动态加载依赖，并检查直达可执行文件及导入工作目录可用。旧的预构建 distribution 只能证明镜像封装与客户端启动，不能证明 Java 业务流程或真实 DMP 还原通过；真实归档和数据库还原仍须按上面的发布门槛单独验收。

## 2026-11-03 主线程验收记录（初版 wrapper 实现）

方案由主线程修订，实现由 Luna Max 子代理完成，主线程独立审阅并验收。实现源码提交为 `d4703f72`；验收中发现的通用 Dockerfile 构建回归、嵌套校验清单遗漏、ELF 头检查和失败构建容器清理问题均已修复。根 Dockerfile、`.dockerignore` 与通用多架构发布 workflow 相对本次实现前无变化。

| 验收项 | 结果与范围 |
| --- | --- |
| 仓库测试与静态检查 | 17 项测试、shell 语法、`git diff --check` 通过 |
| 主线程独立输入检查 | 17 项通过：有效 ARM64 包/相对链接、篡改、漏文件、嵌套同名清单、缺工具/执行权限、错误元数据、x86 ELF、截断 ELF、脚本冒充工具、失效/绝对/越界链接等 |
| 初版 wrapper 检查（历史记录） | 两个 wrapper 曾通过独立 ARM64 可执行探针；本文件末尾的 2026-11-03 修订已移除 wrapper，后续验收以直接工具调用和子进程环境测试为准 |
| 最终候选镜像 | 本机真实 ARM64 Docker 构建成功；镜像为 `seatunnel-web-api:1.0.0-native-arm64-acceptance`，ID 为 `sha256:525a229db9070e8a8efa6569f765556208eb3aa76db0ec43572cf1fc3199f676` |
| 最终镜像运行检查 | 文件及链接 SHA-256 校验通过，默认工具路径正确，实际默认应用用户可写工作目录，JVM 无全局 `LD_LIBRARY_PATH`；Java 21.0.11+10、Kingbase V008R006C009B0014 和达梦帮助启动检查通过；Kingbase 所需三个 Schema/预检参数存在，无效归档被原厂工具拒绝 |
| 动态依赖失败门槛 | 有意移除达梦 OpenSSL 后，即使 `ldd` 无缺失，最终构建仍因启动探针失败而拒绝；失败中间容器及脚本创建的临时后端标签均已清理 |

本次客户端来自本机安装树中精确选取的工具/库，仅作为 ABI 与镜像封装验收输入。达梦报告 `dimp V8`、`file_version: 33`，安装树 `dmserver` 的版本线索为 `03134284552-20260414-322369-20221`；未将该线索当作正式原厂包来源证明。达梦子树独立补入 `openssl-libs-1.1.1f-31.p29.ky10.aarch64` 的两个库与无版本号相对链接；Kingbase 子树补入 `openldap-2.4.50-8.ky10.aarch64`、`cyrus-sasl-lib-2.1.27-15.ky10.aarch64` 中精确需要的三个库，未复制宿主机 glibc 或整个系统库目录。验收 bundle 的 `SHA256SUMS` 摘要为 `7135db4c4927af444a0eb7960b5d948958146506b6729393b40174efa882d065`。

候选镜像使用现有 `seatunnel-web-dist/target/seatunnel-web-1.0.0.tar.gz`，本次没有重新构建 Java distribution。没有执行 Web 任务提交、两家真实 DMP 还原、对象/数据核对、Schema 映射或应用任务超时/日志脱敏/清理的业务端到端验收。x86 容器中的 Kingbase V009R001C010 不属于首期范围，不要求验证其归档能否由 ARM V8 工具读取。正式发布仍须固定可追溯的原厂 bundle 与当前应用 distribution，并完成上述本地达梦和 Kingbase 业务组合验收。该次镜像验收未推送、部署或重启既有容器，未运行 Compose。

## 2026-11-03 直接调用与内网部署修订验收

本次移除镜像中的两个 wrapper，环境库路径由 `DmpCommandBuilder` 放入对应 `ProcessBuilder` 子进程；`.env` 的两个本地开发路径已改为直接指向达梦和 Kingbase 客户端文件。内网方案进一步区分 Web 客户端和独立数据库服务：若需新建数据库，交付完整的两套 ARM64 服务端介质并由 root 编排安装；Web 镜像仍只带客户端。数据库安装/守护进程使用 `kingbase`、`dmdba` 等厂商规定的专用账号。

| 验收项 | 结果与范围 |
| --- | --- |
| Java 单元测试 | `DmpCommandBuilderTest`、`DmpProcessRunnerTest` 共 24 项通过；覆盖两套直接二进制路径、隔离库路径和 Kingbase 密码环境保留 |
| bundle 与构建脚本 | bundle 模拟测试 15 项通过；`bash -n scripts/build-backend-native-arm64.sh` 通过 |
| 当前代码打包与镜像 | 重建 distribution 后比较其 API JAR 与镜像内 JAR，SHA-256 一致；ARM64 本地构建成功，标签 `seatunnel-web-api:1.0.0-native-arm64-direct-acceptance`，镜像 ID `sha256:bb7bc110b50558ded7c637e70f35b193131bdc0500a940ac11b58e92fa7b7888` |
| 镜像内容与启动探针 | 平台为 `linux/arm64`；最终镜像直接探测两家原厂工具成功，使用直达工具路径；检查确认没有 wrapper 文件或 Kingbase/达梦服务端安装树 |
| 本机直接二进制探测 | Kingbase `sys_restore --version` 报告 `V008R006C009B0014`；达梦 `dimp HELP=Y` 已加载并输出工具版本/帮助，但无输入归档时以非零码退出，不能作为还原成功证据 |
| 本地开发服务 | `.env` 使用直接客户端路径；执行 `scripts/dev-restart.sh` 后后端 `9527` 和前端 `8000` 均监听 |
| 未完成的业务验收 | 未部署 Kingbase 或达梦服务端，未执行真实数据库登录与 DMP 还原；完整业务验收仍按发布门槛单独执行。本次没有推送镜像、执行部署或运行 Compose |

## 2026-11-03 内网一键交付可行性修订

针对 root 希望一次完成内网部署并预置账号的需求，方案建议制作一个离线交付包，包含 Web、Kingbase、达梦三个独立镜像和一个 root 安装入口；不把三个服务塞入同一个 JRE 容器。预设 OS 账号/数据库账号名称与初始化 SQL，首次部署时生成或录入密码并保存到 root 专属凭据文件。Kingbase Docker 初始化参数和数据卷方式可参考厂商手册；达梦服务端镜像必须基于取得的 ARM64/目标 OS 介质单独构建并验证。

本次只更新交付方案，未构建或部署数据库服务端镜像，也未生成一键脚本：当前仓库只包含客户端工具，未提供 Kingbase/达梦 ARM64 服务端介质、许可证和目标 OS 支持矩阵。服务端组件投入制作前，须先固定这三项输入并验收数据库初始化、账号权限、持久化、重启和 Web 连通性。
