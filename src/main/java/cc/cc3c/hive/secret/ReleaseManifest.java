package cc.cc3c.hive.secret;

import java.util.List;

record ReleaseManifest(Integer schema, String version, List<ReleaseArtifact> files) {
}

record ReleaseArtifact(String name, Long size, String sha256) {
}
