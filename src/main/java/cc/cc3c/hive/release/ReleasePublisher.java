package cc.cc3c.hive.release;

import net.lingala.zip4j.ZipFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Release operations used by the running Hive web application.
 */
public final class ReleasePublisher {
    private final ReleaseProperties properties;

    public ReleasePublisher(ReleaseProperties properties) {
        this.properties = properties;
    }

    public void packageDirectory(Path directory, Path archive, Consumer<String> log) throws IOException {
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

    public void publish(String version, Path directory, Consumer<String> log) throws IOException {
        new Publisher(this::cloud, log).publish(properties.resolve(), new Publisher.Options(version, directory));
    }

    public byte[] createTicket(String version, int seconds, Consumer<String> log) throws IOException {
        try (var cloud = cloud(properties.resolve())) {
            return DownloadLinks.create(cloud, version, seconds, log);
        }
    }

    private Cloud cloud(ReleaseConfig config) {
        return new AliyunCliCloud(config, new AliyunCli());
    }

}
