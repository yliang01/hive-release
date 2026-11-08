package cc.cc3c.hive.release.consumer;

import cc.cc3c.hive.release.common.ReleaseArtifact;
import cc.cc3c.hive.release.common.ReleaseManifest;
import cc.cc3c.hive.release.common.SecretException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.testng.Assert.*;

public class ReleaseDownloaderTest {
    @Test
    public void expiredManifestFailsBeforeCreatingDownloadDirectory() {
        Path output = Path.of("target", "test-work", "expired-" + UUID.randomUUID());
        var manifest = new ReleaseManifest(java.util.List.of(
                new ReleaseArtifact("hive-release.zip", 1L, "0".repeat(64), "2000-01-01T00:00:00Z", "https://example.test/program"),
                new ReleaseArtifact("secret.yml.enc", 1L, "0".repeat(64), "2000-01-01T00:00:00Z", "https://example.test/config")));
        expectThrows(SecretException.class, () -> new ReleaseDownloader().downloadConfiguration(manifest, output));
        assertFalse(Files.exists(output));
    }
}
