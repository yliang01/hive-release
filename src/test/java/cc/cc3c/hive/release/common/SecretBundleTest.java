package cc.cc3c.hive.release.common;

import cc.cc3c.hive.encryption.HiveEncryption;
import cc.cc3c.hive.encryption.HiveEncryptionConfig;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.testng.Assert.*;

public class SecretBundleTest {
    private static final char[] PASSWORD = "poc-unlock-password-2026".toCharArray();

    @Test
    public void roundTripUsesExistingEncryptionFormat() throws Exception {
        byte[] first = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        byte[] second = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        assertEquals(first, second);
        assertEquals(SecretBundle.decrypt(first, PASSWORD), TestFixtures.properties());
        assertFalse(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("POC-NOT-A-REAL-SECRET"));
        byte[] bytes = new String(PASSWORD).getBytes(StandardCharsets.UTF_8);
        var cipher = new HiveEncryption(new HiveEncryptionConfig(bytes, bytes), bytes);
        var output = new ByteArrayOutputStream();
        cipher.decryptStream(new ByteArrayInputStream(first), output);
        assertEquals(output.toByteArray(), TestFixtures.properties());
    }

    @Test
    public void wrongPasswordFailsForTestData() throws Exception {
        byte[] data = SecretBundle.encrypt(TestFixtures.properties(), PASSWORD);
        expectThrows(SecretException.class, () -> SecretBundle.decrypt(data, "incorrect-password".toCharArray()));
    }

    @Test
    public void ciphertextModificationIsNotAuthenticated() {
        byte[] plaintext = TestFixtures.properties();
        byte[] ciphertext = SecretBundle.encrypt(plaintext, PASSWORD);
        ciphertext[0] ^= 1;
        assertFalse(Arrays.equals(SecretBundle.decrypt(ciphertext, PASSWORD), plaintext));
    }

    @Test
    public void unicodePasswordsUseUtf8() {
        char[] password = "密码测试密码测试abcdef".toCharArray();
        byte[] plaintext = "配置: 测试\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(SecretBundle.decrypt(SecretBundle.encrypt(plaintext, password), password), plaintext);
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
