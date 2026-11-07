package cc.cc3c.hive.release.publisher;

import java.io.IOException;
import java.nio.file.Path;

interface Cloud extends AutoCloseable {
    void checkVersioning() throws IOException;

    byte[] download(String key) throws IOException;

    void put(String key, Path source) throws IOException;

    String sign(String key, int seconds) throws IOException;

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Factory {
        Cloud create(ReleaseConfig config) throws IOException;
    }
}
