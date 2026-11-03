# 离线导入原生客户端镜像交付方案（ARM64 首期）

适用 SeaTunnel Web 1.0.0、SeaTunnel Engine 3.0.0。内网节点以 ARM 为主，首期实现并验收 `linux/arm64`（aarch64）后端镜像；amd64 客户端打包和业务还原验收留待后续独立实现。数据库服务器的 CPU 架构不必与客户端一致。

## 交付范围与主线

- 主交付方式为：受控 ARM64 客户端 bundle 随构建输入提供，内置到独立的原生客户端后端镜像。厂商二进制、依赖库、凭据和验收数据不进入 Git；构建输入只来自明确指定的 bundle，不扫描或直接复制宿主机整个 `/opt`。
- 保留现有 `Dockerfile`、通用 `backend-runtime` 及其 amd64/arm64 发布流程。新增独立 `Dockerfile.native-arm64` 和 ARM64 原生客户端镜像构建入口，使用带 `native-arm64` 的独立标签；不得将只有 ARM 客户端的产物发布到通用多架构标签。
- 首期提供本地 ARM64 构建脚本，构建完成只产生本地镜像，不自动推送、部署或重启服务。厂商包未接入受控 CI 产物源前，不让现有公网 CI 自动构建原生客户端目标。
- 宿主机只读挂载客户端目录、从客户端镜像阶段复制，作为后续替代方式，首期不扩展实现范围。主线运行时不依赖节点预装客户端。
- DMP 由原厂 `sys_restore`、`dimp` 还原；SQL 脚本仍走现有 JDBC/CopyManager。`ksql`、`disql` 为可选诊断工具，`sys_dump`、`dexp` 用于准备验收样本，不作为 SQL 导入依赖。

## ARM64 bundle 契约

bundle 至少包含以下内容；具体清单字段、命令行参数与实现一同补充，但必须可重复校验：

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

达梦客户端还有 `ldd` 无法展示的运行时动态加载依赖：`libdmdpi` 会通过 `dlopen` 查找未带版本号的 `libcrypto.so` 和 `libssl.so`。因此须在 `dm/bin` 或 `dm/lib` 中随达梦包提供匹配的 ARM64 OpenSSL 实库和包内相对链接，全部纳入 SHA256SUMS，并通过镜像构建中的 `dimp HELP=Y` 启动探针。达梦 wrapper 只设置达梦自己的 `bin`、`lib` 搜索路径，不能依赖 Jammy 中只有版本化名称的 OpenSSL runtime，也不能借用 Kingbase 的 OpenSSL 库路径。不要整套复制宿主机 glibc、动态链接器或 `/usr/lib` 来补依赖。

当前后端基于 `eclipse-temurin:21-jre-jammy`。包的校验和只保证输入可追溯，不能证明 ABI 或数据库版本兼容；还须在最终 Jammy 镜像验证依赖闭包和工具启动。构建中不临时从不受控公网地址下载厂商包；发布记录保存基础镜像实际 digest/ID、bundle 校验和、客户端版本和源码提交。

## 子进程依赖隔离与运行配置

镜像内两家目录分别固定为 `/opt/native-clients/dm`、`/opt/native-clients/kingbase`；Java 配置指向 `/opt/native-clients/bin/dimp`、`/opt/native-clients/bin/sys_restore` 两个独立 wrapper。wrapper 仅为对应工具设置库路径和必要厂商环境，再通过 `exec` 启动真实工具，逐个保留原始参数及退出码。

不得向 JVM 或镜像全局设置两家合并的 `LD_LIBRARY_PATH`；wrapper 不继承外部注入的库搜索路径，只包含对应客户端的已验证目录和系统默认搜索路径。保留 Java 设置的临时数据库认证环境。厂商有额外环境或资源依赖时随该 bundle 记录，不改变任务工作目录，以保留现有 DMP 文件和日志行为。

原生客户端镜像提供以下默认值，通用镜像继续保持工具路径留空；现有配置仍允许显式覆盖：

```text
SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/bin/dimp
SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/bin/sys_restore
SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import
SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240
SEATUNNEL_WEB_OFFLINE_IMPORT_SQL_STATEMENT_TIMEOUT_SECONDS=300
```

工作目录须能由实际应用运行用户写入。凭据仅由平台数据源在启动对应原厂子进程时传入；Kingbase 使用临时 `KINGBASE_PASSWORD`。凭据不写入清单、镜像层、Dockerfile 静态 `ENV` 或 build args，执行命令与工具日志沿用现有脱敏。

## 版本与归档兼容矩阵

“ARM64 客户端连接 x86 数据库成功”仅是连接证据，不能视作 DMP 还原通过。每个生产组合必须记录：客户端 CPU 架构、导出工具完整版本、源数据库完整版本与兼容模式、归档格式、还原工具完整版本、目标数据库完整版本与兼容模式，以及最终镜像中的还原结果。

| 候选组合 | 已知信息 | 首期发布要求 |
| --- | --- | --- |
| 达梦 ARM64 | 本机 `/opt/dmdbms/bin` 有 `dimp`、`dexp` 及配套动态库；完整版本待采集 | 固定同版本工具和源/目标库信息，单文件逻辑 DMP 实际预检与还原通过 |
| Kingbase ARM64 V008R006C009B0014 | 原客户端布局为 `ClientTools/bin`、`ClientTools/lib`，含 OpenSSL 1.1、LDAP 2.4 依赖 | 明确其可读取的归档与目标库组合，验证现有 `--strict-names`、`--fromSchema`、`--targetSchema` 参数 |
| Kingbase V009R001C010 目标库 | 现存 x86 容器中的版本，不代表 ARM V8 还原工具与 V9 归档已兼容 | 使用 V9 侧生成的实际归档验证 ARM 工具；不兼容则取得匹配 ARM 客户端，或将该组合标记为不支持 |

首期只接受现有单文件上传流程能够处理的逻辑归档：Kingbase 验收基线使用 `sys_dump -Fc` 自定义格式（不能用默认 SQL 输出改名为 `.dmp`）；达梦使用 `dexp` 生成的单文件逻辑 DMP。目录备份、物理备份和分卷归档不属于本次新增范围。跨版本不自动推断兼容；未验证的组合保留为待验证，不能写成支持。

参考：[Kingbase sys_dump](https://help.kingbase.com.cn/v8/admin/reference/ref-client/sys_dump.html)、[Kingbase sys_restore](https://help.kingbase.com.cn/v8/admin/reference/ref-client/sys_restore.html)、[达梦导入导出 FAQ](https://eco.dameng.com/document/dm/zh-cn/faq/faq-import-export.html)。

## 实现与主线程验收

1. 子代理实现 bundle 校验、独立 wrapper、ARM64 原生客户端镜像目标和本地构建入口，补齐操作命令及必要测试；每个独立功能以中文 Conventional Commit 小步提交。
2. 构建在 ARM64 节点执行，目标显式固定为 `linux/arm64`。构建前验证输入；最终镜像中检查两套工具的 `ldd` 无缺失、版本/帮助能够运行、wrapper 和工作目录可由应用用户使用。错误架构、无效校验和、缺工具须在明确阶段失败。
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
SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/bin/dimp
SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/bin/sys_restore
SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import
SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240
SEATUNNEL_WEB_OFFLINE_IMPORT_SQL_STATEMENT_TIMEOUT_SECONDS=300
```

该示例仅说明运行配置；本次交付不会修改或执行 Compose。

校验器的最小模拟测试覆盖清单覆盖（包括嵌套同名 `SHA256SUMS`）、内容篡改、完整 ELF64 头、必需工具 ELF 架构、x86 ELF 混入和越界链接；wrapper 测试覆盖参数原样传递、当前目录、库路径隔离、`KINGBASE_PASSWORD` 保留及工具退出码：

```bash
python3 scripts/native-arm64/test_native_arm64.py
```

构建脚本会在最终 Jammy 镜像构建时运行两套工具的 `ldd` 缺库检查、`sys_restore --version` 和 DIMP 帮助启动探针；DIMP 探针同时验证 `ldd` 不会显示的 OpenSSL 动态加载依赖，并检查 wrapper 可执行及导入工作目录可写。旧的预构建 distribution 只能证明镜像封装与客户端启动，不能证明 Java 业务流程或真实 DMP 还原通过；真实归档和数据库还原仍须按上面的发布门槛单独验收。

## 2026-11-03 主线程验收记录

方案由主线程修订，实现由 Luna Max 子代理完成，主线程独立审阅并验收。实现源码提交为 `d4703f72`；验收中发现的通用 Dockerfile 构建回归、嵌套校验清单遗漏、ELF 头检查和失败构建容器清理问题均已修复。根 Dockerfile、`.dockerignore` 与通用多架构发布 workflow 相对本次实现前无变化。

| 验收项 | 结果与范围 |
| --- | --- |
| 仓库测试与静态检查 | 17 项测试、shell 语法、`git diff --check` 通过 |
| 主线程独立输入检查 | 17 项通过：有效 ARM64 包/相对链接、篡改、漏文件、嵌套同名清单、缺工具/执行权限、错误元数据、x86 ELF、截断 ELF、脚本冒充工具、失效/绝对/越界链接等 |
| wrapper 进程检查 | 两个 wrapper 均通过独立 ARM64 可执行探针：空参数、空格/特殊字符/中文/换行参数、工作目录、库路径隔离、模拟认证环境、退出码、exec 前后 PID 一致和 SIGTERM 直接终止 |
| 最终候选镜像 | 本机真实 ARM64 Docker 构建成功；镜像为 `seatunnel-web-api:1.0.0-native-arm64-acceptance`，ID 为 `sha256:525a229db9070e8a8efa6569f765556208eb3aa76db0ec43572cf1fc3199f676` |
| 最终镜像运行检查 | 文件及链接 SHA-256 校验通过，默认工具路径正确，实际默认应用用户可写工作目录，JVM 无全局 `LD_LIBRARY_PATH`；Java 21.0.11+10、Kingbase V008R006C009B0014 和达梦帮助启动检查通过；Kingbase 所需三个 Schema/预检参数存在，无效归档被原厂工具拒绝 |
| 动态依赖失败门槛 | 有意移除达梦 OpenSSL 后，即使 `ldd` 无缺失，最终构建仍因启动探针失败而拒绝；失败中间容器及脚本创建的临时后端标签均已清理 |

本次客户端来自本机安装树中精确选取的工具/库，仅作为 ABI 与镜像封装验收输入。达梦报告 `dimp V8`、`file_version: 33`，安装树 `dmserver` 的版本线索为 `03134284552-20260414-322369-20221`；未将该线索当作正式原厂包来源证明。达梦子树独立补入 `openssl-libs-1.1.1f-31.p29.ky10.aarch64` 的两个库与无版本号相对链接；Kingbase 子树补入 `openldap-2.4.50-8.ky10.aarch64`、`cyrus-sasl-lib-2.1.27-15.ky10.aarch64` 中精确需要的三个库，未复制宿主机 glibc 或整个系统库目录。验收 bundle 的 `SHA256SUMS` 摘要为 `7135db4c4927af444a0eb7960b5d948958146506b6729393b40174efa882d065`。

候选镜像使用现有 `seatunnel-web-dist/target/seatunnel-web-1.0.0.tar.gz`，本次没有重新构建 Java distribution。没有执行 Web 任务提交、两家真实 DMP 还原、对象/数据核对、Schema 映射或应用任务超时/日志脱敏/清理的业务端到端验收，也没有验证 Kingbase V8 工具读取 V9 归档。正式发布仍须固定可追溯的原厂 bundle 与当前应用 distribution，并完成上述真实业务组合验收。未推送、部署或重启既有服务，未运行 Compose。
