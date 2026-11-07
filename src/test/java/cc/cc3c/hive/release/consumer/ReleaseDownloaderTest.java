package cc.cc3c.hive.release.consumer;

import cc.cc3c.hive.release.common.DownloadTicket;
import cc.cc3c.hive.release.common.SecretException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.testng.Assert.*;

public class ReleaseDownloaderTest {
    @Test
    public void expiredTicketFailsBeforeCreatingDownloadDirectory() {
        Path output = Path.of("target", "test-work", "expired-" + UUID.randomUUID());
        var ticket = new DownloadTicket("v1", "2000-01-01T00:00:00Z", "https://example.test/program",
                "https://example.test/config", "https://example.test/manifest");
        expectThrows(SecretException.class, () -> new ReleaseDownloader().downloadConfiguration(ticket, output));
        assertFalse(Files.exists(output));
    }
}
