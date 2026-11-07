package cc.cc3c.hive.release.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class TestFixtures {
    static Path temporaryDirectory(String prefix) throws Exception {
        Path parent = Path.of("target", "test-tmp").toAbsolutePath();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, prefix);
    }

    static byte[] properties() {
        var values = Map.of("hive.encryption.salt", "poc-salt-not-for-real-files", "hive.encryption.password", "poc-data-password-not-a-secret",
                "hive.oss.alibaba.accessKeyId", "POC-NOT-A-REAL-ACCESS-KEY", "hive.oss.alibaba.accessKeySecret", "POC-NOT-A-REAL-SECRET",
                "hive.oss.alibaba.endPoint", "https://oss.invalid", "hive.oss.alibaba.backupBucket", "poc-not-a-real-bucket");
        return values.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue() + "\n").collect(java.util.stream.Collectors.joining()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
