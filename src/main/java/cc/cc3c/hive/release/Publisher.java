package cc.cc3c.hive.release;

import cc.cc3c.hive.secret.Installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class Publisher {

    record Options(String version, Path directory) {
    }

    private final Cloud.Factory cloudFactory;
    private final Consumer<String> output;

    Publisher(Cloud.Factory cloudFactory, Consumer<String> output) {
        this.cloudFactory = cloudFactory;
        this.output = output;
    }

    static String hash(Path path) throws IOException {
        return Installer.hash(path);
    }

    private void writeManifest(Options options) throws IOException {
        var files = new ArrayList<Object>();
        for (var name : List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE)) {
            var path = options.directory().resolve(name);
            files.add(Json.object("name", name, "size", Files.size(path), "sha256", hash(path)));
        }
        Json.write(options.directory().resolve(ReleasePaths.MANIFEST),
                Json.object("schema", 1, "version", options.version(), "files", files));
        output.accept("Manifest: " + options.directory().resolve(ReleasePaths.MANIFEST).toAbsolutePath());
    }

    void publish(ReleaseConfig config, Options options) throws IOException {
        writeManifest(options);
        try (var cloud = cloudFactory.create(config)) {
            cloud.checkVersioning();
            for (var name : List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE, ReleasePaths.MANIFEST)) {
                var key = "releases/" + options.version() + "/" + name;
                cloud.put(key, options.directory().resolve(name));
                output.accept("Uploaded: " + key);
            }
            output.accept("Publication complete: oss://" + config.bucket() + "/releases/" + options.version() + "/");
        }
    }

}
