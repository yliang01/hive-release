package cc.cc3c.hive.release.consumer;

import java.nio.file.Path;

public record DownloadResult(String version, Path configuration, Path manifest) { }
