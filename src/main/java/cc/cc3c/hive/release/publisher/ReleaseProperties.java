package cc.cc3c.hive.release.publisher;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hive.release")
public class ReleaseProperties {
    private String profile;
    private String bucket;
    private String roleArn;
    private String region;
    private String sessionName;
    private Long durationSeconds;

    ReleaseConfig resolve() {
        if (durationSeconds == null) throw new ReleaseException("Missing required property: hive.release.duration-seconds");
        return new ReleaseConfig(required(profile, "profile"), required(bucket, "bucket"),
                required(roleArn, "role-arn"), required(region, "region"), required(sessionName, "session-name"), durationSeconds);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank())
            throw new ReleaseException("Missing required property: hive.release." + field);
        return value;
    }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }
}
