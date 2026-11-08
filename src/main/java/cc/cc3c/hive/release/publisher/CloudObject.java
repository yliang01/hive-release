package cc.cc3c.hive.release.publisher;

import java.time.Instant;

record CloudObject(String key, Instant lastModified) { }
