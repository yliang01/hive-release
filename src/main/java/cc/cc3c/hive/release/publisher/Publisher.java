package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.FileHash;
import cc.cc3c.hive.release.common.ReleaseArtifact;
import cc.cc3c.hive.release.common.ReleaseManifest;
import cc.cc3c.hive.release.common.ReleasePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class Publisher {

    record Options(String version, Path directory) { }

    private static final String RELEASE_PREFIX = "releases/";
    private static final List<String> RELEASE_FILES = List.of(
            ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE, ReleasePaths.MANIFEST);

    private final Cloud.Factory cloudFactory;
    private final Consumer<String> output;

    Publisher(Cloud.Factory cloudFactory, Consumer<String> output) {
        this.cloudFactory = cloudFactory;
        this.output = output;
    }

    static String hash(Path path) throws IOException {
        return FileHash.sha256(path);
    }

    private void writeManifest(Options options) throws IOException {
        var files = new ArrayList<ReleaseArtifact>();
        for (var name : List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE)) {
            var path = options.directory().resolve(name);
            files.add(new ReleaseArtifact(name, Files.size(path), hash(path)));
        }
        Json.write(options.directory().resolve(ReleasePaths.MANIFEST),
                new ReleaseManifest(files));
        output.accept("Manifest: " + options.directory().resolve(ReleasePaths.MANIFEST).toAbsolutePath());
    }

    void publish(ReleaseConfig config, Options options) throws IOException {
        ReleaseVersion.requireValid(options.version());
        writeManifest(options);
        try (var cloud = cloudFactory.create(config)) {
            cloud.checkVersioning();
            for (var name : RELEASE_FILES) {
                var key = RELEASE_PREFIX + options.version() + "/" + name;
                cloud.put(key, options.directory().resolve(name));
                output.accept("Uploaded: " + key);
            }
            output.accept("Publication complete: oss://" + config.bucket() + "/" + RELEASE_PREFIX + options.version() + "/");
        }
    }

    List<PublishedRelease> list(ReleaseConfig config) throws IOException {
        try (var cloud = cloudFactory.create(config)) {
            return list(cloud);
        }
    }

    private List<PublishedRelease> list(Cloud cloud) throws IOException {
        Map<String, Set<String>> filesByVersion = new HashMap<>();
        Map<String, Instant> modifiedByVersion = new HashMap<>();
        for (CloudObject object : cloud.list(RELEASE_PREFIX)) {
            if (!object.key().startsWith(RELEASE_PREFIX)) continue;
            String relative = object.key().substring(RELEASE_PREFIX.length());
            int separator = relative.indexOf('/');
            if (separator <= 0 || separator == relative.length() - 1) continue;
            String version = relative.substring(0, separator);
            String file = relative.substring(separator + 1);
            if (file.contains("/") || !RELEASE_FILES.contains(file) || !isValidVersion(version)) continue;
            filesByVersion.computeIfAbsent(version, ignored -> new HashSet<>()).add(file);
            if (ReleasePaths.MANIFEST.equals(file)) modifiedByVersion.put(version, object.lastModified());
        }

        List<PublishedRelease> releases = new ArrayList<>();
        for (var entry : filesByVersion.entrySet()) {
            if (entry.getValue().size() != RELEASE_FILES.size()) continue;
            try {
                DownloadLinks.validateManifest(cloud, entry.getKey());
                releases.add(new PublishedRelease(entry.getKey(), modifiedByVersion.get(entry.getKey())));
            } catch (ReleaseException ignored) {
                // Invalid manifests do not define a completed release.
            }
        }
        releases.sort(Comparator.comparing(PublishedRelease::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(PublishedRelease::version));
        return List.copyOf(releases);
    }

    void delete(ReleaseConfig config, List<String> versions) throws IOException {
        try (var cloud = cloudFactory.create(config)) {
            cloud.checkVersioning();
            Set<String> published = new HashSet<>();
            for (PublishedRelease release : list(cloud)) published.add(release.version());
            for (String version : versions) {
                if (!published.contains(version)) throw new ReleaseException("所选版本不存在或发布不完整：" + version);
            }
            for (String version : versions) DownloadLinks.validateManifest(cloud, version);
            for (String version : versions) {
                cloud.delete(RELEASE_PREFIX + version + "/" + ReleasePaths.PROGRAM_ARCHIVE);
                cloud.delete(RELEASE_PREFIX + version + "/" + ReleasePaths.SECRET_BUNDLE);
                cloud.delete(RELEASE_PREFIX + version + "/" + ReleasePaths.MANIFEST);
            }
        }
    }

    private boolean isValidVersion(String version) {
        try {
            ReleaseVersion.requireValid(version);
            return true;
        } catch (ReleaseException ignored) {
            return false;
        }
    }
}
