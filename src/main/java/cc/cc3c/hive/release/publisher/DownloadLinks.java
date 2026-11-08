package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.ReleaseArtifact;
import cc.cc3c.hive.release.common.ReleaseManifest;
import cc.cc3c.hive.release.common.ReleasePaths;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class DownloadLinks {
    private DownloadLinks() { }

    static ReleaseManifest createManifest(Cloud cloud, String version, int seconds, Consumer<String> output) throws IOException {
        ReleaseVersion.requireValid(version);
        ReleaseManifest manifest = validateManifest(cloud, version);
        Instant expires = Instant.now().plusSeconds(seconds);
        var signedFiles = new ArrayList<ReleaseArtifact>(manifest.files().size());
        for (ReleaseArtifact file : manifest.files()) {
            String key = "releases/" + version + "/" + file.name();
            String url = ReleasePaths.PROGRAM_ARCHIVE.equals(file.name())
                    ? cloud.sign(key, seconds, "attachment;filename=hive-release-" + version + ".zip")
                    : cloud.sign(key, seconds);
            signedFiles.add(new ReleaseArtifact(file.name(), file.size(), file.sha256(), expires.toString(), url));
        }
        output.accept("Manifest expires: " + expires + ". Signed URLs are not printed; treat the manifest as a secret.");
        return new ReleaseManifest(signedFiles);
    }

    static ReleaseManifest validateManifest(Cloud cloud, String version) throws IOException {
        byte[] bytes = cloud.download("releases/" + version + "/" + ReleasePaths.MANIFEST);
        ReleaseManifest manifest;
        try {
            manifest = Json.MAPPER.readValue(bytes, ReleaseManifest.class);
        } catch (JsonProcessingException e) {
            throw new ReleaseException("Invalid release manifest.");
        }
        if (manifest == null || manifest.files() == null) throw new ReleaseException("Invalid release manifest.");

        var names = new ArrayList<String>();
        for (ReleaseArtifact file : manifest.files()) {
            if (file == null || file.name() == null || file.size() == null || file.size() < 1
                    || file.sha256() == null || !file.sha256().matches("[a-f0-9]{64}"))
                throw new ReleaseException("Invalid manifest file metadata.");
            names.add(file.name());
        }
        if (names.size() != 2 || !names.containsAll(List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE)))
            throw new ReleaseException("Unexpected manifest files.");
        return manifest;
    }
}
