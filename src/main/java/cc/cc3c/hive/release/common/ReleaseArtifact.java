package cc.cc3c.hive.release.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReleaseArtifact(String name, Long size, String sha256,
                              @JsonProperty("expires_at") String expiresAt, String url) {
    public ReleaseArtifact(String name, Long size, String sha256) {
        this(name, size, sha256, null, null);
    }

    @Override
    public String toString() {
        return "ReleaseArtifact[name=" + name + ", size=" + size + ", sha256=" + sha256
                + ", expiresAt=" + expiresAt + ", url=REDACTED]";
    }
}
