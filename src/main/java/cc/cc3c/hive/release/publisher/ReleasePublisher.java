package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.DownloadTicket;
import cc.cc3c.hive.release.common.FileHash;
import cc.cc3c.hive.release.common.ReleasePaths;
import cc.cc3c.hive.release.common.SecretBundle;
import net.lingala.zip4j.ZipFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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

    public DownloadTicket createTicket(String version, Duration validity, Consumer<String> log) throws IOException {
        try (var cloud = cloudFactory.create(properties.resolve())) {
            return DownloadLinks.create(cloud, version, Math.toIntExact(validity.toSeconds()), log);
        }
    }

}
