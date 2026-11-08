package cc.cc3c.hive.release.publisher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface Cloud extends AutoCloseable {
    void checkVersioning() throws IOException;

    List<CloudObject> list(String prefix) throws IOException;

    byte[] download(String key) throws IOException;

    void put(String key, Path source) throws IOException;

    void delete(String key) throws IOException;

    String sign(String key, int seconds) throws IOException;

    default String sign(String key, int seconds, String responseContentDisposition) throws IOException {
        return sign(key, seconds);
    }

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Factory {
        Cloud create(ReleaseConfig config) throws IOException;
    }
}
