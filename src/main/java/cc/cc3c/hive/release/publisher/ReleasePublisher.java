package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.FileHash;
import cc.cc3c.hive.release.common.ReleaseManifest;
import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.SecretBundle;
import net.lingala.zip4j.ZipFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Release operations used by the running Hive web application.
 */
public final class ReleasePublisher {
    private final ReleaseProperties properties;
    private final Cloud.Factory cloudFactory;

    public ReleasePublisher(ReleaseProperties properties) {
        this(properties, config -> new AliyunCliCloud(config, new AliyunCli()));
    }

    ReleasePublisher(ReleaseProperties properties, Cloud.Factory cloudFactory) {
        this.properties = properties;
        this.cloudFactory = cloudFactory;
    }

    void packageDirectory(Path directory, Path archive, Consumer<String> log) throws IOException {
        Path source = directory.toAbsolutePath().normalize();
        Path destination = archive.toAbsolutePath().normalize();
        log.accept("正在打包指定目录：" + source);
        if (!Files.isDirectory(source)) {
            throw new IOException("Source is not a directory: " + source);
        }
        if (destination.startsWith(source)) {
            throw new IOException("Archive must be outside the source directory: " + destination);
        }
        Files.createFile(destination);
        new ZipFile(destination.toFile()).addFolder(source.toFile());
    }

    public PublishResult publish(String version, Path programDirectory, byte[] configurationYaml, char[] password,
                                 Path outputDirectory, Consumer<String> log) throws IOException {
        ReleaseVersion.requireValid(version);
        var config = properties.resolve();
        Files.createDirectories(outputDirectory);
        Path archive = outputDirectory.resolve(ReleasePaths.PROGRAM_ARCHIVE);
        Path encrypted = outputDirectory.resolve(ReleasePaths.SECRET_BUNDLE);
        Path manifest = outputDirectory.resolve(ReleasePaths.MANIFEST);
        packageDirectory(programDirectory, archive, log);
        Files.write(encrypted, SecretBundle.encrypt(configurationYaml, password), StandardOpenOption.CREATE_NEW);
        String archiveHash = FileHash.sha256(archive);
        String configurationHash = FileHash.sha256(encrypted);
        new Publisher(cloudFactory, log).publish(config, new Publisher.Options(version, outputDirectory));
        return new PublishResult(version, archive, encrypted, manifest, archiveHash, configurationHash);
    }

    public ReleaseManifest createManifest(String version, Duration validity, Consumer<String> log) throws IOException {
        ReleaseVersion.requireValid(version);
        ReleaseConfig config = properties.resolve();
        validateValidity(validity, config);
        try (var cloud = cloudFactory.create(config)) {
            return DownloadLinks.createManifest(cloud, version, Math.toIntExact(validity.toSeconds()), log);
        }
    }

    public List<PublishedRelease> listPublishedReleases() throws IOException {
        return new Publisher(cloudFactory, ignored -> { }).list(properties.resolve());
    }

    public void deleteReleases(List<String> versions) throws IOException {
        List<String> selectedVersions = validateVersions(versions);
        new Publisher(cloudFactory, ignored -> { }).delete(properties.resolve(), selectedVersions);
    }

    private static List<String> validateVersions(List<String> versions) {
        if (versions == null || versions.isEmpty() || versions.size() > 100)
            throw new ReleaseException("请选择 1 到 100 个版本。");
        var unique = new HashSet<String>();
        for (String version : versions) {
            ReleaseVersion.requireValid(version);
            if (!unique.add(version)) throw new ReleaseException("版本列表不能包含重复项。");
        }
        return List.copyOf(versions);
    }

    private static void validateValidity(Duration validity, ReleaseConfig config) {
        if (validity == null || validity.isZero() || validity.isNegative()
                || validity.getNano() != 0 || validity.getSeconds() > config.durationSeconds())
            throw new ReleaseException("Manifest URL 有效期必须为正整数秒，且不超过临时凭据有效期。");
    }

}
