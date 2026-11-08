package cc.cc3c.hive.release.consumer;

import cc.cc3c.hive.release.common.FileHash;
import cc.cc3c.hive.release.common.ReleaseArtifact;
import cc.cc3c.hive.release.common.ReleaseManifest;
import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.SecretBundle;
import cc.cc3c.hive.release.common.SecretException;

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
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Downloads the encrypted configuration described by an issued manifest; no cloud SDK or credentials.
 */
public final class ReleaseDownloader {
    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Download only the encrypted configuration; the imported manifest already contains its URL and hash. */
    public DownloadResult downloadConfiguration(ReleaseManifest manifest) throws IOException, InterruptedException {
        return downloadConfiguration(manifest, ReleasePaths.downloadDirectory());
    }

    public DownloadResult downloadConfiguration(ReleaseManifest manifest, Path downloadDirectory) throws IOException, InterruptedException {
        Map<String, ReleaseArtifact> files = validateManifest(manifest);
        ReleaseArtifact configuration = files.get(ReleasePaths.SECRET_BUNDLE);
        Path target = downloadDirectory.resolve(UUID.randomUUID().toString());
        Files.createDirectories(target);
        Path bundle = target.resolve(ReleasePaths.SECRET_BUNDLE);
        try {
            download(checkedUrl(configuration), bundle, configuration.size());
            verifyHash(bundle, configuration.sha256());
            return new DownloadResult(bundle);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(bundle);
            throw e;
        }
    }

    private void download(URI uri, Path target, long expectedSize) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).GET().build();
        var response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        if (response.statusCode() != 200) {
            body.close();
            throw new SecretException("Download failed; request details suppressed.");
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength >= 0 && contentLength != expectedSize) {
            body.close();
            throw new SecretException("Downloaded artifact size does not match the manifest.");
        }
        try (body; var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = body.read(buffer)) != -1) {
                total += count;
                if (total > expectedSize) throw new SecretException("Downloaded artifact exceeds its manifest size.");
                out.write(buffer, 0, count);
            }
            if (total != expectedSize) throw new SecretException("Downloaded artifact size does not match the manifest.");
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private static Map<String, ReleaseArtifact> validateManifest(ReleaseManifest manifest) {
        if (manifest == null || manifest.files() == null || manifest.files().size() != 2)
            throw new SecretException("Invalid release manifest.");
        var files = new HashMap<String, ReleaseArtifact>();
        for (ReleaseArtifact artifact : manifest.files()) {
            if (artifact == null || artifact.name() == null || artifact.size() == null || artifact.size() < 1
                    || artifact.sha256() == null || !artifact.sha256().matches("[a-f0-9]{64}"))
                throw new SecretException("Invalid manifest file metadata.");
            if (ReleasePaths.SECRET_BUNDLE.equals(artifact.name()) && artifact.size() > SecretBundle.MAX_BYTES)
                throw new SecretException("Encrypted configuration exceeds the supported size.");
            if (!artifact.name().equals(ReleasePaths.PROGRAM_ARCHIVE)
                    && !artifact.name().equals(ReleasePaths.SECRET_BUNDLE))
                throw new SecretException("Unexpected manifest files.");
            if (files.putIfAbsent(artifact.name(), artifact) != null)
                throw new SecretException("Duplicate manifest file.");
            checkExpiration(artifact.expiresAt());
            checkedUrl(artifact);
        }
        if (!files.containsKey(ReleasePaths.PROGRAM_ARCHIVE) || !files.containsKey(ReleasePaths.SECRET_BUNDLE))
            throw new SecretException("Unexpected manifest files.");
        return files;
    }

    private static URI checkedUrl(ReleaseArtifact artifact) {
        if (artifact.url() == null || artifact.url().isBlank())
            throw new SecretException("Invalid manifest download URL.");
        try {
            URI uri = URI.create(artifact.url());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new SecretException("Manifest download URLs require HTTPS.");
            return uri;
        } catch (IllegalArgumentException e) {
            throw new SecretException("Invalid manifest download URL.");
        }
    }

    private static void checkExpiration(String expiresAt) {
        try {
            if (expiresAt == null || !Instant.parse(expiresAt).isAfter(Instant.now()))
                throw new SecretException("Manifest download link is expired.");
        } catch (DateTimeParseException e) {
            throw new SecretException("Invalid manifest expiration time.");
        }
    }

    private static void verifyHash(Path file, String expected) throws IOException {
        if (!FileHash.sha256(file).equals(expected))
            throw new SecretException("Downloaded artifact failed SHA-256 verification.");
    }
}
