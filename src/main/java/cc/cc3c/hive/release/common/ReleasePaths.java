package cc.cc3c.hive.release.common;

import java.nio.file.Path;

public final class ReleasePaths {
    public static final String ROOT = ".release";
    public static final String BUILD = "build";
    public static final String UPLOAD = "upload";
    public static final String DOWNLOAD = "download";
    public static final String PROGRAM_ARCHIVE = "hive-release.zip";
    public static final String SECRET_BUNDLE = "secret.yml.enc";
    public static final String MANIFEST = "manifest.json";
    public static final String CURRENT_SELECTION = "current.txt";

    private ReleasePaths() {
    }

    public static Path buildDirectory() {
        return Path.of(ROOT, BUILD).toAbsolutePath();
    }

    public static Path uploadDirectory() {
        return Path.of(ROOT, UPLOAD).toAbsolutePath();
    }

    public static Path downloadDirectory() {
        return Path.of(ROOT, DOWNLOAD).toAbsolutePath();
    }
}
