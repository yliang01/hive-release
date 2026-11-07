package cc.cc3c.hive.release.publisher;

import java.nio.file.Path;

public record PublishResult(String version, Path programArchive, Path encryptedConfiguration, Path manifest,
                            String archiveSha256, String configurationSha256) { }
