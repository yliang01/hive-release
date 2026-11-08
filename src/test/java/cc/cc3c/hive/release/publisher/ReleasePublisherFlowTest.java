package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.SecretBundle;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;

import static org.testng.Assert.*;

public class ReleasePublisherFlowTest {
    @Test
    public void publishesPreparedFilesAndReturnsSignedManifest() throws Exception {
        Path root = Path.of("target", "test-work");
        Files.createDirectories(root);
        Path work = Files.createTempDirectory(root, "flow-");
        Path program = Files.createDirectories(work.resolve("build"));
        Files.writeString(program.resolve("app.txt"), "test program");
        byte[] yaml = "integration: test\n".getBytes(StandardCharsets.UTF_8);
        char[] password = "unit-test-password".toCharArray();
        var properties = new ReleaseProperties();
        properties.setProfile("test");
        properties.setBucket("test-bucket");
        properties.setRoleArn("role");
        properties.setRegion("cn-shanghai");
        properties.setSessionName("test");
        properties.setDurationSeconds(3600L);
        var cloud = new ReleaseTest.FakeCloud();
        var publisher = new ReleasePublisher(properties, ignored -> cloud);
        var logs = new ArrayList<String>();

        PublishResult result = publisher.publish("v1", program, yaml, password, work.resolve("upload"), logs::add);
        assertEquals(result.version(), "v1");
        assertTrue(Files.isRegularFile(result.programArchive()));
        assertTrue(Files.isRegularFile(result.manifest()));
        assertEquals(SecretBundle.decrypt(result.encryptedConfiguration(), password), yaml);
        assertEquals(result.archiveSha256(), cc.cc3c.hive.release.common.FileHash.sha256(result.programArchive()));
        assertEquals(result.configurationSha256(), cc.cc3c.hive.release.common.FileHash.sha256(result.encryptedConfiguration()));
        assertEquals(cloud.uploads.size(), 3);

        var manifest = publisher.createManifest("v1", Duration.ofSeconds(900), logs::add);
        var configuration = manifest.files().stream().filter(file -> file.name().equals("secret.yml.enc")).findFirst().orElseThrow();
        assertTrue(configuration.url().startsWith("https://"));
        assertFalse(manifest.toString().contains("https://"));
        assertFalse(String.join("\n", logs).contains("https://"));
        var serialized = Json.MAPPER.valueToTree(manifest);
        assertTrue(serialized.path("files").isArray());
        assertTrue(serialized.path("files").get(0).has("expires_at"));
        assertTrue(serialized.path("files").get(0).has("url"));
        assertFalse(serialized.has("version"));
        assertFalse(serialized.has("schema"));
    }
}
