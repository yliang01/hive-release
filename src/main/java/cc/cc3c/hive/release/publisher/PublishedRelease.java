package cc.cc3c.hive.release.publisher;

import java.time.Instant;

public record PublishedRelease(String version, Instant publishedAt) { }
