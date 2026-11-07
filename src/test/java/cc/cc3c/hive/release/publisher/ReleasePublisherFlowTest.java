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
    public void publishesPreparedFilesAndReturnsTypedTicket() throws Exception {
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

        var ticket = publisher.createTicket("v1", Duration.ofSeconds(900), logs::add);
        assertEquals(ticket.version(), "v1");
        assertTrue(ticket.configurationUrl().startsWith("https://"));
        assertFalse(ticket.toString().contains("https://"));
        assertFalse(String.join("\n", logs).contains("https://"));
        var serialized = Json.MAPPER.valueToTree(ticket);
        assertTrue(serialized.has("configuration_url"));
        assertTrue(serialized.has("program_url"));
        assertTrue(serialized.has("manifest_url"));
    }
}
