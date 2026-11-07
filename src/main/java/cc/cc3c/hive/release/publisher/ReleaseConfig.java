package cc.cc3c.hive.release.publisher;

record ReleaseConfig(String profile, String bucket, String roleArn, String region, String sessionName, long durationSeconds) {
}
