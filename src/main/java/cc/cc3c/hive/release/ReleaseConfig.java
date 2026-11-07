package cc.cc3c.hive.release;

record ReleaseConfig(String profile, String bucket, String roleArn, String region, String sessionName, long durationSeconds) {
}
