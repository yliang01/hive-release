package cc.cc3c.hive.release.publisher;

import java.util.regex.Pattern;

final class ReleaseVersion {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    private ReleaseVersion() { }

    static String requireValid(String version) {
        if (version == null || !VALID.matcher(version).matches())
            throw new ReleaseException("版本号格式无效。");
        return version;
    }
}
