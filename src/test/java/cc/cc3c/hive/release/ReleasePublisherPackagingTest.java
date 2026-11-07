package cc.cc3c.hive.release;

import org.testng.annotations.Test;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;

import static org.testng.Assert.*;

public class ReleasePublisherPackagingTest {
    @Test
    public void packsDirectoryAndPreservesExistingArchives() throws Exception {
        Files.createDirectories(Path.of("target/test-work"));
        Path work = Files.createTempDirectory(Path.of("target/test-work"), "pack-");
        Path source = Files.createDirectories(work.resolve("build"));
        Files.createDirectories(source.resolve("empty"));
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("nested/data.txt"), "content");
        Path archive = work.resolve("release.zip");
        var publisher = new ReleasePublisher(new ReleaseProperties());

        publisher.packageDirectory(source, archive, ignored -> { });

        try (var zip = FileSystems.newFileSystem(archive, Map.of())) {
            assertTrue(Files.isDirectory(zip.getPath("/build/empty")));
            assertEquals(Files.readString(zip.getPath("/build/nested/data.txt")), "content");
        }
        assertThrows(FileAlreadyExistsException.class, () -> publisher.packageDirectory(source, archive, ignored -> { }));
        assertThrows(IOException.class, () -> publisher.packageDirectory(source, source.resolve("release.zip"), ignored -> { }));
        assertFalse(Files.exists(source.resolve("release.zip")));
    }
}
