package cc.cc3c.hive.release;

import cc.cc3c.hive.secret.SecretBundle;
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
        System.out.println("Integration test OSS prefix: releases/" + version + "/");
        Path workRoot = Path.of("target", "integration-work");
        Files.createDirectories(workRoot);
        Path work = Files.createTempDirectory(workRoot, "release-");
        Path source = Files.createDirectories(work.resolve("build"));
        Path upload = Files.createDirectories(work.resolve("upload"));
        byte[] program = "hive-release integration test file\n".getBytes(StandardCharsets.UTF_8);
        byte[] yaml = "integration-test: true\n".getBytes(StandardCharsets.UTF_8);
        char[] password = "integration-test-password".toCharArray();
        Files.write(source.resolve("test-file.txt"), program);

        var publisher = new ReleasePublisher(properties);
        Path archive = upload.resolve(ReleasePaths.PROGRAM_ARCHIVE);
        Path bundle = upload.resolve(ReleasePaths.SECRET_BUNDLE);
        publisher.packageDirectory(source, archive, ignored -> { });
        Files.write(bundle, SecretBundle.encrypt(yaml, password));
        publisher.publish(version, upload, ignored -> { });

        byte[] ticketBytes = publisher.createTicket(version, 300, ignored -> { });
        DownloadTicket ticket = Json.MAPPER.readValue(ticketBytes, DownloadTicket.class);
        assertEquals(ticket.version(), version);
        assertTrue(Instant.parse(ticket.expiresAt()).isAfter(Instant.now()));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        byte[] downloadedArchive = download(http, ticket.programUrl());
        byte[] downloadedBundle = download(http, ticket.configurationUrl());
        byte[] downloadedManifest = download(http, ticket.manifestUrl());
        assertEquals(downloadedArchive, Files.readAllBytes(archive));
        assertEquals(downloadedBundle, Files.readAllBytes(bundle));
        assertEquals(downloadedManifest, Files.readAllBytes(upload.resolve(ReleasePaths.MANIFEST)));
        assertEquals(SecretBundle.decrypt(downloadedBundle, password), yaml);
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
        expectThrows(IOException.class, () -> publisher.publish(version, upload, ignored -> { }));
        assertEquals(download(http, ticket.manifestUrl()), downloadedManifest);
    }

    private static byte[] download(HttpClient http, String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(response.statusCode(), 200, "Signed download failed");
        return response.body();
    }
}
