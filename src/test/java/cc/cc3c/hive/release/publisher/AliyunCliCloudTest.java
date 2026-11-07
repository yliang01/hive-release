package cc.cc3c.hive.release.publisher;

import org.testng.annotations.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

public class AliyunCliCloudTest {
    private static final String STS = "{\"Credentials\":{\"AccessKeyId\":\"test-id\",\"AccessKeySecret\":\"test-secret\",\"SecurityToken\":\"test-token\"}}";
    private static ReleaseConfig config() { return new ReleaseConfig("release-profile", "bucket", "role-arn", "cn-shanghai", "session", 3600); }

    @Test
    public void usesExpectedCommandsAndSelectedProfile() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        var assumedRoles = new AtomicInteger();
        var cloud = new AliyunCliCloud(config(), (profile, credentials, args) -> {
            assertEquals(profile, "release-profile");
            if (args.getFirst().equals("sts")) {
                assumedRoles.incrementAndGet();
                assertTrue(credentials.isEmpty());
                assertEquals(args, List.of("sts", "AssumeRole", "--RoleArn", "role-arn", "--RoleSessionName", "session",
                        "--DurationSeconds", "3600", "--region", "cn-shanghai", "--profile", "release-profile"));
                return STS.getBytes(StandardCharsets.UTF_8);
            }
            assertEquals(credentials.get("ALIBABA_CLOUD_ACCESS_KEY_ID"), "test-id");
            assertEquals(credentials.get("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), "test-secret");
            assertEquals(credentials.get("ALIBABA_CLOUD_SECURITY_TOKEN"), "test-token");
            assertEquals(credentials.get("ALIBABA_CLOUD_REGION_ID"), "cn-shanghai");
            assertEquals(args.subList(args.size() - 2, args.size()), List.of("--region", "cn-shanghai"));
            commands.add(args);
            if (args.contains("get-bucket-versioning")) return "{\"+@xmlns\":\"oss\"}".getBytes(StandardCharsets.UTF_8);
            if (args.contains("cat")) return "manifest".getBytes(StandardCharsets.UTF_8);
            if (args.contains("sign")) return "https://example.test/object?signature=secret\n\n0.1s elapsed".getBytes(StandardCharsets.UTF_8);
            return new byte[0];
        });
        cloud.checkVersioning();
        cloud.put("releases/v1/file.zip", Path.of("target", "a b.zip"));
        assertEquals(new String(cloud.download("releases/v1/manifest.json"), StandardCharsets.UTF_8), "manifest");
        assertTrue(cloud.sign("releases/v1/file.zip", 900).startsWith("https://"));
        assertEquals(assumedRoles.get(), 1);
        assertTrue(commands.get(1).containsAll(List.of("--forbid-overwrite", "--cache-control", "no-store")));
        assertTrue(commands.get(1).stream().anyMatch(s -> s.startsWith("file://") && s.endsWith("a b.zip")));
        assertTrue(commands.get(3).containsAll(List.of("oss", "sign", "--timeout", "900")));
    }

    @Test
    public void rejectsVersionedAndInvalidResults() throws Exception {
        for (String response : List.of("{\"Status\":\"Enabled\"}", "{\"Status\":\"Suspended\"}", "{\"Status\":\"unknown\"}")) {
            var cloud = new AliyunCliCloud(config(), (p, c, a) -> (a.getFirst().equals("sts") ? STS : response).getBytes(StandardCharsets.UTF_8));
            expectThrows(ReleaseException.class, cloud::checkVersioning);
        }
        var invalid = new AliyunCliCloud(config(), (p, c, a) -> (a.getFirst().equals("sts") ? STS : "invalid").getBytes(StandardCharsets.UTF_8));
        expectThrows(IOException.class, invalid::checkVersioning);
        expectThrows(IOException.class, () -> invalid.sign("key", 60));
    }

    @Test
    public void rejectsInvalidStsCredentialsBeforeOss() {
        var calls = new ArrayList<List<String>>();
        var cloud = new AliyunCliCloud(config(), (p, c, a) -> {
            calls.add(a);
            return "{\"Credentials\":{}}".getBytes(StandardCharsets.UTF_8);
        });
        expectThrows(IllegalArgumentException.class, cloud::checkVersioning);
        assertEquals(calls.size(), 1);
    }

    @Test
    public void failedAssumeRoleStopsBeforeAnyOssCommand() {
        var calls = new ArrayList<List<String>>();
        var cloud = new AliyunCliCloud(config(), (profile, credentials, args) -> {
            calls.add(args);
            throw new IOException("STS failed");
        });
        expectThrows(IOException.class, cloud::checkVersioning);
        assertEquals(calls.size(), 1);
        assertEquals(calls.getFirst().getFirst(), "sts");
    }

    @Test
    public void closingSessionForcesFreshCredentials() throws Exception {
        var sessions = new AtomicInteger();
        var cloud = new AliyunCliCloud(config(), (profile, credentials, args) -> {
            if (args.getFirst().equals("sts")) {
                sessions.incrementAndGet();
                return STS.getBytes(StandardCharsets.UTF_8);
            }
            return "{}".getBytes(StandardCharsets.UTF_8);
        });
        cloud.checkVersioning();
        cloud.checkVersioning();
        assertEquals(sessions.get(), 1);
        cloud.close();
        cloud.checkVersioning();
        assertEquals(sessions.get(), 2);
    }
}
