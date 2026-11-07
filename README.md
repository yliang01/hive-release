# Hive Release and Encrypted Configuration Starter

Java 21 / Spring Boot 3 starter，供 hive-oss UI 注入使用。云端操作通过本机阿里云 CLI 和其 ossutil 组件完成。

## 接入

应用依赖 yliang:hive-release:1.0，Spring Boot 自动加载 ReleaseAutoConfiguration 并注册 ReleasePublisher。其他项目可直接复用或提供自己的
ReleasePublisher bean。

```java
public MyService(ReleasePublisher publisher) {
    this.publisher = publisher;
}
```

方法：packageDirectory（目录打包）、publish（生成 manifest 并上传）、createTicket（签发 ticket）。UI 的一次发布请求自动完成目录打包、YAML
加密和上传。

## Encrypted configuration

`SecretBundle.encrypt(byte[], char[])` encrypts the original YAML bytes with AES-256-GCM. Decryption returns those same
bytes; the bundle does not flatten, whitelist, or otherwise convert YAML fields. The `HIVEYML1` envelope uses
PBKDF2-HMAC-SHA256 with 600,000 iterations, a fresh salt, and a fresh nonce. The distribution filename is
`secret.yml.enc`; legacy `.hcfg` files are unsupported.

The setup flow loads decrypted YAML through Spring's `YamlPropertySourceLoader` after unlock. Plain YAML can also be
loaded through `/#/setup`. The UI downloads configuration from the ticket's fixed `configuration_url` and checks its
SHA-256 value against the manifest. Program installation is handled outside the starter. The setup flow stores downloads
under `.release/download/<UUID>/`, and `.release/download/current.txt` records the selected encrypted file after
successful business startup.

`SecretBundle`, `Installer`, and `SecretException` live in the `cc.cc3c.hive.secret` package and ship in this starter
alongside the release publisher. There is no separate `hive-secret` artifact or CLI.

## 配置

安装阿里云 CLI（需支持 `aliyun ossutil`）并预先安装 ossutil 组件，确保 `aliyun` 在 Hive 进程的 PATH 中。配置操作员 CLI profile，
并授权其调用 STS AssumeRole 扮演发布角色。Hive 不直接读取 CLI 凭据文件，也不使用 CLI 当前默认 profile：每次发布或签发 ticket 时，
调用 CLI 的 `sts AssumeRole`，将返回的临时凭据通过子进程环境传给 OSS 命令；本地目录打包不访问云端。
CLI 的 ossutil 组件缺失时可能触发其自身的下载流程。

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

Hive 使用程序当前工作目录下的 `.release`：build 固定存放待打包程序，upload 固定存放发布产物。setup context 将配置下载到同一目录下的
download 子目录。启动脚本会先切换到程序所在目录；从 IDE 启动时，目录由 IDE 的工作目录设置决定。

以上字段均必须填写。`credentials-file`、`connection-timeout-millis`、`socket-timeout-millis` 和 `max-error-retry` 不再由 Hive 使用；
凭据文件由 CLI 管理，网络参数使用 CLI 默认值。所选 profile 不应使用业务 OSS 身份。

## 目录打包

ReleasePublisher.packageDirectory(directory, output, log) 递归打包指定目录，以目录名作为 ZIP 顶层目录，保留文件和空目录。它不寻找当前运行的
JAR，不读取 JAR 内模板，不补充或过滤源文件。输出必须在源目录之外，且不能覆盖已有文件。

UI 通过 ReleasePublisher.packageDirectory 使用程序当前工作目录下 `.release/build`。源码仓库的 package-release.cmd
在构建后调用根目录 assemble-release.cmd，把 JAR 和 src/distribution 中明确列出的启动文件与 README
组装到该目录。组装脚本只存在于源码仓库，不进入发布包。其他调用者需要自行准备输入目录；缺目录或文件读取失败直接报错。starter
只负责目录打包和发布，不负责构建或复制项目资源。目录内容原样打包；发布阶段不检查文件大小、后缀、ZIP 内容、版本格式或本地快照哈希。

## 发布行为

每次发布或签发 ticket 调用一次 CLI `sts AssumeRole`，随后使用同一组临时凭据调用 `ossutil api get-bucket-versioning`、
`ossutil api put-object`、`ossutil cat` 和 `oss sign`。失败显示退出码以及可识别的 HTTP 状态、错误码和 request ID，不输出原始命令输出、凭据或签名 URL。

每个版本包含 hive-release.zip、secret.yml.enc、manifest.json。检查 Bucket 版本控制状态，PutObject 设置
x-oss-forbid-overwrite=true，按顺序上传，最后上传 manifest.json，作为发布完成标记。发布过程不下载云端对象、不比较已有内容、不自动跳过已有对象。失败保留云端对象和本次
`.release/upload` 文件，没有删除或回滚 API 调用；已存在对象的版本需要改用新版本号发布。

publish(version, directory, log) 在已有 ZIP、加密 YAML 的发布目录内生成 manifest，再直接上传三个文件。没有额外发布子目录或重复复制。

下载端使用 SHA-256 校验文件完整性，没有额外发行者签名。

ticket 有效期由调用者显式指定，签名链接最终可用时间由 CLI、OSS 与身份凭据有效期决定。签发时在内存读取
manifest、生成 ticket 字节并返回浏览器，不创建目录、不保存文件。ticket 必须私密传递，解锁密码通过独立渠道提供。

操作说明见 [self-release](../hive-oss/SELF-RELEASE.md)
，阿里云基础设施见 [infra/aliyun-infra](../infra/aliyun-infra/README.md)。

## 手动阿里云集成测试

普通 Maven 构建只运行单元测试。`aliyun-integration` profile 使用 Maven Failsafe 运行 `AliyunReleaseIT`，实际调用本机阿里云 CLI、STS 和 OSS。
请准备专用测试 Bucket 和允许扮演发布角色的 CLI profile；Bucket 不得启用或暂停版本控制，角色需要查询 Bucket 版本控制、读写对象的权限。

先在 `src/test/java/cc/cc3c/hive/release/AliyunReleaseIT.java` 中设置 `PROFILE`、`BUCKET`、`ROLE_ARN` 和 `REGION`，
然后在 PowerShell 中从项目根目录手动运行：

```powershell
.\mvnw.cmd -f hive-release\pom.xml -Paliyun-integration verify
```

测试创建随机版本 `releases/integration-test-<UUID>/`，上传测试 ZIP 和加密配置，签发 ticket 并通过签名链接下载验证，最后验证同版本不可覆盖。
测试只使用生成的测试文件和密码，不使用业务配置；执行时会打印测试版本。测试不会删除云端对象，成功或失败都可能留下该前缀下的对象，
可根据打印的前缀自行清理。Failsafe 报告位于 `hive-release/target/failsafe-reports/`。
