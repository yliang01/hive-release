package cc.cc3c.hive.release.common;

import cc.cc3c.hive.encryption.HiveEncryption;
import cc.cc3c.hive.encryption.HiveEncryptionConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Encrypts and restores original YAML bytes using hive-encryption. */
public final class SecretBundle {
    public static final int MAX_BYTES = 1024 * 1024;

    private SecretBundle() { }

    public static byte[] encrypt(byte[] yaml, char[] password) {
        if (password.length < 12) throw new SecretException("Use an unlock password of at least 12 characters.");
        if (yaml.length >= MAX_BYTES) throw new SecretException("Secret YAML is too large.");
        return transform(yaml, password, true);
    }

    public static byte[] decrypt(Path file, char[] password) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return decrypt(input.readNBytes(MAX_BYTES + 1), password);
        }
    }

    public static byte[] decrypt(byte[] ciphertext, char[] password) {
        if (ciphertext.length == 0 || ciphertext.length > MAX_BYTES || ciphertext.length % 16 != 0)
            throw new SecretException("Invalid encrypted YAML size.");
        return transform(ciphertext, password, false);
    }

    private static byte[] transform(byte[] input, char[] password, boolean encrypt) {
        var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);
        try {
            var cipher = new HiveEncryption(new HiveEncryptionConfig(passwordBytes, passwordBytes), passwordBytes);
            var output = new ByteArrayOutputStream();
            if (encrypt) cipher.encryptStream(new ByteArrayInputStream(input), output);
            else cipher.decryptStream(new ByteArrayInputStream(input), output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new SecretException(encrypt ? "Secret YAML encryption failed." : "Cannot unlock YAML: wrong password or damaged file.");
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
