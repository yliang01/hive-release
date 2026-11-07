package cc.cc3c.hive.secret;

import cc.cc3c.hive.release.DownloadTicket;
import cc.cc3c.hive.release.ReleasePaths;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Downloads only: no cloud SDK, account credential, upload or delete operation.
 */
public final class Installer {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = SecretBundle.JSON.copy()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Download just the configuration for a program already installed; no activation.
     */
    public Path downloadConfiguration(String ticketContent) throws Exception {
        var ticket = readJson(ticketContent.getBytes(java.nio.charset.StandardCharsets.UTF_8), DownloadTicket.class);
        checkExpiration(ticket);
        Path target = ReleasePaths.downloadDirectory().resolve(UUID.randomUUID().toString());
        Files.createDirectories(target);
        Path manifestPath = target.resolve(ReleasePaths.MANIFEST);
        Path bundle = target.resolve(ReleasePaths.SECRET_BUNDLE);
        download(URI.create(ticket.manifestUrl()), manifestPath);
        download(URI.create(ticket.configurationUrl()), bundle);
        verifyHash(bundle, ReleasePaths.SECRET_BUNDLE, readJson(manifestPath, ReleaseManifest.class));
        return bundle;
    }

    private void download(URI uri, Path target) throws Exception {
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
        if (!hash(file).equals(expected)) throw new SecretException("Downloaded artifact failed SHA-256 verification.");
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

    private static <T> T readJson(byte[] bytes, Class<T> type) throws IOException {
        try {
            return JSON.readValue(bytes, type);
        } catch (IOException e) {
            throw new SecretException("Invalid release metadata; details suppressed.");
        }
    }

    public static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
