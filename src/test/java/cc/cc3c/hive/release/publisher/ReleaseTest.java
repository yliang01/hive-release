package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.ReleaseManifest;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

import static org.testng.Assert.*;

public class ReleaseTest {
    private Path root;
    private FakeCloud cloud;
    private Publisher publisher;
    private final ReleaseConfig config = new ReleaseConfig("test", "test-bucket", "acs:ram::123:role/test", "cn-shanghai", "test", 3600);

    private Publisher.Options options() {
        return new Publisher.Options("test-version", root.resolve("inputs"));
    }

    @BeforeMethod
    public void setup() throws IOException {
        Files.createDirectories(Path.of("target/test-work"));
        root = Files.createTempDirectory(Path.of("target/test-work"), "release-");
        Files.createDirectories(root.resolve("inputs"));
        var parameters = new ZipParameters();
        parameters.setFileNameInZip("hive-oss.jar");
        try (var zip = new ZipFile(root.resolve("inputs").resolve(ReleasePaths.PROGRAM_ARCHIVE).toFile())) {
            zip.addStream(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}), parameters);
        }
        Files.write(root.resolve("inputs").resolve(ReleasePaths.SECRET_BUNDLE), new byte[]{1, 2, 3});
        cloud = new FakeCloud();
        publisher = new Publisher(c -> cloud, ignored -> { });
    }

    @Test
    public void manifestLastAndOverwriteRefused() throws Exception {
        publisher.publish(config, options());
        assertEquals(cloud.uploads.stream().map(k -> k.substring(k.lastIndexOf('/') + 1)).toList(),
                List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE, ReleasePaths.MANIFEST));
        expectThrows(IOException.class, () -> publisher.publish(config, options()));
        assertEquals(cloud.uploads.size(), 3);
    }

    @Test
    public void manifestContainsPublishedFilesSizesAndHashes() throws Exception {
        publisher.publish(config, options());
        byte[] bytes = cloud.objects.get("releases/test-version/" + ReleasePaths.MANIFEST);
        var manifest = Json.MAPPER.readTree(bytes);
        var sharedManifest = Json.MAPPER.readValue(bytes, ReleaseManifest.class);
        assertEquals(sharedManifest.version(), "test-version");
        assertEquals(sharedManifest.files().stream().map(artifact -> artifact.name()).toList(),
                List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE));
        assertEquals(manifest.path("schema").asInt(), 1);
        assertEquals(manifest.path("version").asText(), "test-version");
        assertEquals(manifest.path("files").size(), 2);
        for (var file : manifest.path("files")) {
            var source = root.resolve("inputs").resolve(file.path("name").asText());
            assertEquals(file.path("size").asLong(), Files.size(source));
            assertEquals(file.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        }
    }

    @Test
    public void interruptedPublicationPreservesObjects() throws Exception {
        cloud.failPut = ReleasePaths.MANIFEST;
        expectThrows(IOException.class, () -> publisher.publish(config, options()));
        assertEquals(cloud.objects.size(), 2);
        assertFalse(cloud.objects.containsKey("releases/test-version/" + ReleasePaths.MANIFEST));
        cloud.failPut = null;
        expectThrows(IOException.class, () -> publisher.publish(config, options()));
        assertEquals(cloud.objects.size(), 2);
    }

    @Test
    public void publicationDoesNotDownloadObjects() throws Exception {
        cloud.failDownload = true;
        publisher.publish(config, options());
        assertEquals(cloud.uploads.size(), 3);
    }

    @Test
    public void versionedBucketStopsBeforeWrites() {
        cloud.versioned = true;
        expectThrows(ReleaseException.class, () -> publisher.publish(config, options()));
        assertTrue(cloud.uploads.isEmpty());
    }

    @Test
    public void linksValidateManifestAndNeverPrintUrls() throws Exception {
        publisher.publish(config, options());
        var messages = new ArrayList<String>();
        var ticket = DownloadLinks.create(cloud, "test-version", 900, messages::add);
        var ticketJson = Json.MAPPER.valueToTree(ticket);
        assertTrue(ticketJson.path("program_url").isTextual());
        assertTrue(ticketJson.path("configuration_url").isTextual());
        assertTrue(ticketJson.path("manifest_url").isTextual());
        assertEquals(cloud.signed, List.of("releases/test-version/hive-release.zip:900",
                "releases/test-version/secret.yml.enc:900", "releases/test-version/manifest.json:900"));
        assertFalse(String.join("\n", messages).contains("https://"));
        assertFalse(ticket.toString().contains("https://"));
        cloud.objects.put("releases/test-version/" + ReleasePaths.MANIFEST, "{}".getBytes(StandardCharsets.UTF_8));
        expectThrows(ReleaseException.class, () -> DownloadLinks.create(cloud, "test-version", 900, ignored -> { }));
    }


    static class FakeCloud implements Cloud {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> uploads = new ArrayList<>();
        final List<String> signed = new ArrayList<>();
        String failPut;
        boolean failDownload, versioned;

        public void checkVersioning() {
            if (versioned) throw new ReleaseException("Versioning enabled");
        }

        public byte[] download(String key) throws IOException {
            if (failDownload) throw new IOException("AccessDenied");
            var value = objects.get(key);
            if (value == null) {
                throw new IOException("NoSuchKey");
            }
            return value;
        }

        public void put(String key, Path source) throws IOException {
            if (failPut != null && key.endsWith(failPut)) throw new IOException("Upload interrupted");
            if (objects.containsKey(key)) throw new IOException("Overwrite refused");
            objects.put(key, Files.readAllBytes(source));
            uploads.add(key);
        }

        public String sign(String key, int seconds) {
            signed.add(key + ":" + seconds);
            return "https://example.com/" + key + "?Signature=test";
        }

        public void close() {
        }
    }
}
