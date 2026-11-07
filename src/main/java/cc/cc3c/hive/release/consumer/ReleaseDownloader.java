package cc.cc3c.hive.release.consumer;

import cc.cc3c.hive.release.common.DownloadTicket;
import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.SecretException;
import cc.cc3c.hive.release.common.FileHash;
import cc.cc3c.hive.release.common.ReleaseArtifact;
import cc.cc3c.hive.release.common.ReleaseManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Downloads only: no cloud SDK, account credential, upload or delete operation.
 */
public final class ReleaseDownloader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Download just the configuration for a program already installed; no activation.
     */
    public DownloadResult downloadConfiguration(DownloadTicket ticket) throws IOException, InterruptedException {
        return downloadConfiguration(ticket, ReleasePaths.downloadDirectory());
    }

    public DownloadResult downloadConfiguration(DownloadTicket ticket, Path downloadDirectory) throws IOException, InterruptedException {
        checkExpiration(ticket);
        Path target = downloadDirectory.resolve(UUID.randomUUID().toString());
        Files.createDirectories(target);
        Path manifestPath = target.resolve(ReleasePaths.MANIFEST);
        Path bundle = target.resolve(ReleasePaths.SECRET_BUNDLE);
        download(URI.create(ticket.manifestUrl()), manifestPath);
        download(URI.create(ticket.configurationUrl()), bundle);
        verifyHash(bundle, ReleasePaths.SECRET_BUNDLE, readJson(manifestPath, ReleaseManifest.class));
        return new DownloadResult(ticket.version(), bundle, manifestPath);
    }

    private void download(URI uri, Path target) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new SecretException("Download tickets require HTTPS URLs.");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).GET().build();
        var response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        if (response.statusCode() != 200) {
            body.close();
            throw new SecretException("Download failed; request details suppressed.");
        }
        try (body; var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = body.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static void checkExpiration(DownloadTicket ticket) {
        if (!Instant.parse(ticket.expiresAt()).isAfter(Instant.now()))
            throw new SecretException("Download ticket is expired.");
    }

    private static void verifyHash(Path file, String name, ReleaseManifest manifest) throws IOException {
        String expected = manifest.files().stream()
                .filter(artifact -> artifact.name().equals(name))
                .map(ReleaseArtifact::sha256)
                .findFirst().orElse(null);
        if (!FileHash.sha256(file).equals(expected)) throw new SecretException("Downloaded artifact failed SHA-256 verification.");
    }

    private static <T> T readJson(Path path, Class<T> type) throws IOException {
        try (var in = Files.newInputStream(path)) {
            try {
                return JSON.readValue(in, type);
            } catch (IOException e) {
                throw new SecretException("Invalid release metadata; details suppressed.");
            }
        }
    }

}
