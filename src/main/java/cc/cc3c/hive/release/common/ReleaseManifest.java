package cc.cc3c.hive.release.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseManifest(List<ReleaseArtifact> files) { }
