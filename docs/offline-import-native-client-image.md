# 离线导入原生客户端镜像交付说明

适用 SeaTunnel Web 1.0.0、SeaTunnel Engine 3.0.0。本文记录后续 Web 后端镜像打包 Kingbase 与达梦还原客户端的要求；本次只补充文档，没有修改镜像、脚本或二进制。

## 运行依赖与配置

- DMP 还原运行时需要与目标数据库版本匹配的 Kingbase `sys_restore` 和达梦 `dimp`。SQL 脚本走 JDBC 执行；Kingbase 使用官方 CopyManager，因此 `ksql`、`disql` 是可选诊断工具，不是 SQL 导入依赖。Kingbase `sys_dump`、达梦 `dexp` 用于生成验收样本。
- `application.yml` 已提供 `SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH`、`SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH`、`SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR` 和 `SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES`。前两个路径默认留空；工作目录默认 `tmp/offline-import`，超时默认 240 分钟。工作目录必须可写。

示例路径仅供后续镜像布局参考，需随实际客户端包调整；数据库凭据仍由平台数据源提供，不放入镜像或环境变量：

```text
SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/dm/bin/dimp
SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/kingbase/bin/sys_restore
SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import
SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240
```

## 镜像交付约束

当前后端阶段使用 `eclipse-temurin:21-jre-jammy`，只提供 Java 运行环境；`.dockerignore` 没有把原生客户端包纳入当前构建上下文。因此不能直接从宿主机 `/opt` 路径 `COPY` 文件，也不能只复制 `dimp` 或 `sys_restore` 可执行文件。交付包需同时保留厂商客户端的动态库、依赖库和执行权限，并为应用进程提供正确的库加载路径及可写 scratch 目录。

生产镜像应选择以下一种受控来源：把经过版本校验的客户端 bundle 纳入发布产物，或从与目标 CPU 架构及数据库版本匹配的客户端镜像阶段用 Docker `COPY --from` 复制；也可将客户端目录只读挂载到容器中的固定路径，再设置上述工具路径。Docker 官方文档说明了[多阶段构建](https://docs.docker.com/build/building/multi-stage/)与[只读 bind mount](https://docs.docker.com/engine/storage/bind-mounts/)；挂载方案依赖 Docker daemon 主机上的目录结构，部署前需确保源路径存在。

现场已核实的客户端不能混用：本机 ARM64 达梦客户端在 `/opt/dmdbms/bin`，其中有 `dimp`、`dexp`、`disql` 及 `libdmp_dll`、`libdmdpi`、`libdmfldr`；本机 ARM64 Kingbase V008R006C009B0014 客户端位于 `ClientTools/bin` 与 `ClientTools/lib`，依赖包括 `libkci`、OpenSSL 1.1、LDAP 2.4。现存 Kingbase x86 V009R001C010 容器使用 `/home/kingbase/install/kingbase/bin`。这些是验证现场，不是生产配置；构建时按目标架构和数据库版本选用匹配的原厂 bundle，并分开保存两套客户端及其库。

## 后续镜像验收

在实际最终镜像中检查 `ldd` 没有缺失库，记录客户端 `--version` 或厂商版本信息，并使用厂商生成的 DMP 样本完成端到端还原。另验证应用进程能执行两个工具、工作目录可写且超时配置生效。本次未执行 Docker build、Compose、部署或容器重启；文中没有指定未经验证的原厂镜像名或下载地址。
