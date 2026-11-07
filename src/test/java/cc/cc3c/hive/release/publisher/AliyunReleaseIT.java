package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.DownloadTicket;
import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.SecretBundle;
import cc.cc3c.hive.release.consumer.ReleaseDownloader;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.testng.Assert.*;

/** Opt-in test against an explicitly configured OSS bucket. */
public class AliyunReleaseIT {
    // Set these values for the dedicated test environment before running this test.
    private static final String PROFILE = "terraform-operator";
    private static final String BUCKET = "hive-release";
    private static final String ROLE_ARN = "acs:ram::1568538681445851:role/HiveReleaseRole";
    private static final String REGION = "cn-shanghai";

    @Test
    public void publishesSignsDownloadsAndRejectsOverwrite() throws Exception {
        var properties = new ReleaseProperties();
        properties.setProfile(PROFILE);
        properties.setBucket(BUCKET);
        properties.setRoleArn(ROLE_ARN);
        properties.setRegion(REGION);
        properties.setSessionName("hive-release-it");
        properties.setDurationSeconds(3600L);

        String version = "integration-test-" + UUID.randomUUID();
        log("OSS 测试位置: " + BUCKET + "/releases/" + version + "/");
        Path workRoot = Path.of("target", "integration-work");
        Files.createDirectories(workRoot);
        Path work = Files.createTempDirectory(workRoot, "release-");
        Path source = Files.createDirectories(work.resolve("build"));
        Path upload = Files.createDirectories(work.resolve("upload"));
        byte[] program = "hive-release integration test file\n".getBytes(StandardCharsets.UTF_8);
        byte[] yaml = "integration-test: true\n".getBytes(StandardCharsets.UTF_8);
        char[] password = "integration-test-password".toCharArray();
        Files.write(source.resolve("test-file.txt"), program);
        log("本地测试目录: " + work.toAbsolutePath());

        var publisher = new ReleasePublisher(properties);
        log("[1/6] 打包程序、加密配置并上传发布文件");
        PublishResult result = publisher.publish(version, source, yaml, password, upload, AliyunReleaseIT::log);
        Path archive = result.programArchive();
        Path bundle = result.encryptedConfiguration();

        log("[2/6] 签发并检查下载 ticket");
        DownloadTicket ticket = publisher.createTicket(version, Duration.ofSeconds(300), AliyunReleaseIT::log);
        assertEquals(ticket.version(), version);
        assertTrue(Instant.parse(ticket.expiresAt()).isAfter(Instant.now()));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        log("[3/6] 下载程序 ZIP；使用 ReleaseDownloader 下载并校验配置");
        byte[] downloadedArchive = download(http, ticket.programUrl());
        var downloadResult = new ReleaseDownloader().downloadConfiguration(ticket, work.resolve("download"));
        Path downloadedConfiguration = downloadResult.configuration();
        byte[] downloadedBundle = Files.readAllBytes(downloadedConfiguration);
        byte[] downloadedManifest = Files.readAllBytes(downloadResult.manifest());
        assertEquals(downloadResult.version(), version);
        log("配置下载目录: " + downloadedConfiguration.getParent().toAbsolutePath());
        log("[4/6] 检查下载内容、解密配置并验证程序 ZIP");
        assertEquals(downloadedArchive, Files.readAllBytes(archive));
        assertEquals(downloadedBundle, Files.readAllBytes(bundle));
        assertEquals(downloadedManifest, Files.readAllBytes(upload.resolve(ReleasePaths.MANIFEST)));
        assertEquals(SecretBundle.decrypt(downloadedConfiguration, password), yaml);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(downloadedArchive))) {
            boolean found = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("build/test-file.txt")) {
                    assertEquals(zip.readAllBytes(), program);
                    found = true;
                }
            }
            assertTrue(found, "Test file missing from downloaded ZIP");
        }
        log("[5/6] 检查 manifest 的版本、文件大小和 SHA-256");
        var manifest = Json.MAPPER.readTree(downloadedManifest);
        assertEquals(manifest.path("version").asText(), version);
        assertEquals(manifest.path("files").size(), 2);
        for (var file : manifest.path("files")) {
            byte[] content = switch (file.path("name").asText()) {
                case ReleasePaths.PROGRAM_ARCHIVE -> downloadedArchive;
                case ReleasePaths.SECRET_BUNDLE -> downloadedBundle;
                default -> throw new AssertionError("Unexpected manifest entry");
            };
            assertEquals(file.path("size").asLong(), (long) content.length);
            assertEquals(file.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        }
        log("[6/6] 验证同版本禁止覆盖并确认远端 manifest 不变");
        Path retry = Files.createDirectory(work.resolve("retry"));
        expectThrows(IOException.class, () -> publisher.publish(version, source, yaml, password, retry, ignored -> { }));
        assertEquals(download(http, ticket.manifestUrl()), downloadedManifest);
        log("全流程验证通过；测试对象保留在上述 OSS 前缀下。");
    }

    private static void log(String message) {
        System.out.println("[AliyunReleaseIT] " + message);
    }

    private static byte[] download(HttpClient http, String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(response.statusCode(), 200, "Signed download failed");
        return response.body();
    }
}
