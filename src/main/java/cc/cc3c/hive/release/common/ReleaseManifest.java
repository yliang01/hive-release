package cc.cc3c.hive.release.common;

import java.util.List;

public record ReleaseManifest(Integer schema, String version, List<ReleaseArtifact> files) { }
