# hive-release 模块指南

## 作用与边界

`hive-release` 是 Java 21 / Spring Boot starter，负责发布程序和加密配置、签发下载 ticket，以及在使用方下载并校验加密配置。模块不编译或组装程序，也不负责安装、升级或启动程序。Hive 的管理 UI 不依赖发布凭据启动；业务容器启动后才提供发布服务。

源码位于 `src/main/java/cc/cc3c/hive/release/`，按职责分为：

- `publisher`：`ReleasePublisher` 是发布入口；`AliyunCliCloud` / `AliyunCli` 负责 STS 和 OSS CLI 调用，`DownloadLinks` 签发 ticket。
- `consumer`：`ReleaseDownloader` 下载 manifest 和加密配置，校验配置文件的 SHA-256；不下载程序 ZIP。
- `common`：`ReleaseManifest`、`ReleaseArtifact`、`DownloadTicket`、`ReleasePaths`、`FileHash`、`SecretBundle` 和 `SecretException`。

调用方在 `hive-oss`：发布入口是 `business/release/SelfReleaseService` 和 `SelfReleaseController`，下载及解锁入口是 `setup/SetupService`。发布自动配置为 `publisher/ReleaseAutoConfiguration`，注册在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；`HiveOssApplication` 的管理容器按类名排除它。

## 公开接口和流程

- `ReleasePublisher.publish(version, programDirectory, configurationYaml, password, outputDirectory, log)` 打包指定目录、加密 YAML、生成 manifest 并上传，返回 `PublishResult`（版本、产物路径和哈希）。调用方准备空的产物目录；已有 ZIP 或加密配置不会被覆盖。源目录内容原样进入 ZIP，目录名为 ZIP 顶层目录，输出必须位于源目录外。
- `ReleasePublisher.createTicket(version, Duration, log)` 返回 `DownloadTicket`；调用方负责 JSON 序列化。ticket 的签名链接是敏感信息，`toString()` 不显示 URL，不应输出 URL 或临时凭据到日志。
- `ReleaseDownloader.downloadConfiguration(ticket[, directory])` 下载 manifest 和加密配置并校验配置的 SHA-256，返回 `DownloadResult`。未指定目录时使用当前工作目录下 `.release/download/<UUID>/`。下载与输入密码解锁是两个步骤；`SecretBundle.decrypt` 返回 YAML 原始字节。
- `ReleaseManifest` / `ReleaseArtifact` 是发布方和下载方共用的数据结构。JSON 字段为 `schema`、`version`、`files`、`name`、`size`、`sha256`；修改时保持两端和现有 manifest 一致。

Hive 从当前工作目录的 `.release/build` 打包，在 `.release/upload` 写发布产物，并在 `.release/download` 保存下载结果；`current.txt` 在业务成功启动后记录选中的加密配置路径。`package-release.cmd` 在构建后调用根目录 `assemble-release.cmd`，将 JAR 和 `hive-oss/src/distribution` 中的发布资源组装到 `.release/build`。IDE 的工作目录会影响这些路径。

## 加密格式

`SecretBundle` 调用现有 `hive-encryption-starter`，使用其 AES-CBC 实现：用户密码按 UTF-8 编码后同时作为密码、salt 和 IV 的输入，文件只保存密文，文件名为 `secret.yml.enc`。同一密码和明文会产生相同密文；CBC 没有认证标签，不能可靠检测篡改或错误密码。原 `HIVEYML1` 和 `.hcfg` 格式不受支持；不要假定旧文件可解密。密码至少 12 字符，加密配置的大小受 `SecretBundle.MAX_BYTES` 限制。

配置包加密直接使用用户输入的密码，不依赖 `hive.encryption.salt` / `password`。但传递依赖 `hive-encryption-starter` 会引入它的自动配置：不使用业务加密配置的其他 Spring Boot 应用需要排除 `cc.cc3c.hive.encryption.HiveEncryptionAutoConfiguration`；Hive 的管理容器已排除它。

## 阿里云配置与发布约束

安装阿里云 CLI 和其 ossutil 组件，确保 Hive 进程的 PATH 中有 `aliyun`。`hive.release` 显式配置以下字段：

```yaml
hive:
  release:
    profile: terraform-operator
    bucket: hive-release
    role-arn: acs:ram::YOUR_ACCOUNT_ID:role/HiveReleaseRole
    region: cn-shanghai
    session-name: hive-publish
    duration-seconds: 3600
```

操作员 profile 由 CLI 管理，必须有权限通过 STS AssumeRole 扮演发布角色；发布角色需要相应 OSS 权限。每次发布或签发 ticket 调用一次 `sts AssumeRole`，后续命令使用该会话的临时凭据。OSS 命令显式传入地域；`credentials-file`、超时和重试不再由本模块配置。避免记录 CLI 原始输出、密码、凭据和签名链接。

每个版本写入 `releases/<version>/`：`hive-release.zip`、`secret.yml.enc`、`manifest.json`。发布前检查 Bucket 版本控制状态；启用或暂停版本控制时停止发布。上传使用 `x-oss-forbid-overwrite`，按上述顺序上传，manifest 最后作为完成标记。失败保留已上传对象和本地文件，不自动回滚；有已有对象时使用新版本号。下载校验哈希，但没有发行者签名。ticket 的实际有效时间还受 STS 凭据有效期影响。

## 构建与验证

仓库根目录没有 Maven 聚合 POM。先按项目构建顺序安装 `external-bom` 和 `hive-encryption`，再从模块目录运行 `mvn test`；或从仓库根目录运行：

```powershell
.\mvnw.cmd -f hive-release\pom.xml test
```

普通构建运行单元测试，不运行 `AliyunReleaseIT`。单元测试会在模块 `target/test-work/` 和 `target/test-tmp/` 产生测试文件，`mvn clean` 会清理整个 `target`。

`AliyunReleaseIT` 使用真实阿里云 CLI、STS 和 OSS，必须由用户手动运行。先在 `src/test/java/cc/cc3c/hive/release/publisher/AliyunReleaseIT.java` 设置 `PROFILE`、`BUCKET`、`ROLE_ARN`、`REGION`，使用专用测试 Bucket；再从仓库根目录运行：

```powershell
.\mvnw.cmd -f hive-release\pom.xml -Paliyun-integration verify
```

测试覆盖发布、签票、程序链接下载、`ReleaseDownloader` 下载校验和配置解密，以及同版本覆盖失败。它创建随机的 `releases/integration-test-<UUID>/`，打印前缀，本地文件放在 `target/integration-work/`。测试不会自动删除远端对象；失败也可能留下部分对象。报告位于 `target/failsafe-reports/`。不要自行运行真实云端发布或修改已有秘密文件。

网页端配置与发布流程见 [hive-oss README](../hive-oss/README.md)；应用架构及开发约束见 [hive-oss AGENTS.md](../hive-oss/AGENTS.md)。
