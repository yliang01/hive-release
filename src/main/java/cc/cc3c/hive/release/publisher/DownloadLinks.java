package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.release.common.DownloadTicket;
import cc.cc3c.hive.release.common.ReleasePaths;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class DownloadLinks {
    private DownloadLinks() {
    }

    static DownloadTicket create(Cloud cloud, String version, int seconds, Consumer<String> output) throws IOException {
        var description = Json.MAPPER.readTree(cloud.download("releases/" + version + "/" + ReleasePaths.MANIFEST));
        if (description.path("schema").asInt() != 1 || !version.equals(description.path("version").asText()))
            throw new ReleaseException("Invalid release manifest.");
        var names = new ArrayList<String>();
        for (var file : description.path("files")) {
            if (!file.path("size").isIntegralNumber() || file.path("size").asLong() < 1
                    || !file.path("sha256").asText().matches("[a-f0-9]{64}"))
                throw new ReleaseException("Invalid manifest file metadata.");
            names.add(file.path("name").asText());
        }
        if (names.size() != 2 || !names.containsAll(List.of(ReleasePaths.PROGRAM_ARCHIVE, ReleasePaths.SECRET_BUNDLE)))
            throw new ReleaseException("Unexpected manifest files.");
        var expires = Instant.now().plusSeconds(seconds);
        output.accept("Expires: " + expires + ". URLs are not printed; treat the ticket as a secret.");
        return new DownloadTicket(version, expires.toString(),
                cloud.sign("releases/" + version + "/" + ReleasePaths.PROGRAM_ARCHIVE, seconds),
                cloud.sign("releases/" + version + "/" + ReleasePaths.SECRET_BUNDLE, seconds),
                cloud.sign("releases/" + version + "/" + ReleasePaths.MANIFEST, seconds));
    }
}
