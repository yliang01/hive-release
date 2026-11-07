package cc.cc3c.hive.secret;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.*;

public class SecretBundleTest {
    private static final char[] PASSWORD = "poc-unlock-password-2026".toCharArray();

    @Test
    public void authenticatedRoundTripUsesFreshRandomness() throws Exception {
        byte[] first = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        byte[] second = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        assertFalse(Arrays.equals(first, second));
        assertEquals(SecretBundle.decrypt(first, PASSWORD), TestFixtures.properties());
        assertFalse(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("POC-NOT-A-REAL-SECRET"));
    }

    @Test
    public void wrongPasswordAndTamperingFailClosed() throws Exception {
        byte[] data = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        expectThrows(SecretException.class, () -> SecretBundle.decrypt(data, "incorrect-password".toCharArray()));
        for (int index : List.of(0, 10, 15, 35, data.length - 1)) {
            byte[] modified = data.clone();
            modified[index] ^= 1;
            expectThrows(SecretException.class, () -> SecretBundle.decrypt(modified, PASSWORD));
        }
    }

    @Test
    public void yamlBytesCanBeEncryptedToFile() throws Exception {
        Path root = TestFixtures.temporaryDirectory("hive-release-secret-yaml-");
        Path yaml = root.resolve("private.yml");
        Files.writeString(yaml, "hive:\n  encryption:\n    salt: test-salt\n    password: data-password\n  oss:\n    alibaba:\n      accessKeyId: dummy-id\n      accessKeySecret: dummy-secret\n      endPoint: https://oss.invalid\n      backupBucket: dummy-bucket\n");

        Path bundle = root.resolve("private.yml.enc");
        Files.write(bundle, SecretBundle.encrypt(Files.readAllBytes(yaml), PASSWORD));
        assertEquals(SecretBundle.decrypt(bundle, PASSWORD), Files.readAllBytes(yaml));
    }

    @Test
    public void oversizedInputIsRejected() throws Exception {
        Path root = TestFixtures.temporaryDirectory("hive-release-secret-invalid-");
        Path oversized = root.resolve("oversized.yml.enc");

        Files.write(oversized, new byte[SecretBundle.MAX_BYTES + 1]);
        expectThrows(SecretException.class, () -> SecretBundle.decrypt(oversized, PASSWORD));
    }
}
