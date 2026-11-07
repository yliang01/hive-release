package cc.cc3c.hive.secret;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypts and restores original YAML bytes; no configuration schema or field conversion.
 */
public final class SecretBundle {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final byte[] MAGIC = "HIVEYML1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 600_000, HEADER_SIZE = 40;
    // JSON is only used for release tickets, never for secret contents.
    static final ObjectMapper JSON = new ObjectMapper();

    private SecretBundle() {
    }

    public static byte[] encrypt(byte[] yaml, char[] password) {
        if (password.length < 12) throw new SecretException("Use an unlock password of at least 12 characters.");
        if (yaml.length > MAX_BYTES - HEADER_SIZE - 16) throw new SecretException("Secret YAML is too large.");
        byte[] salt = new byte[16], nonce = new byte[12];
        var random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).array();
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce);
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(yaml);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new SecretException("Secret YAML encryption failed.");
        }
    }

    public static byte[] decrypt(Path file, char[] password) throws IOException {
        return decrypt(readLimited(file), password);
    }

    public static byte[] decrypt(byte[] envelope, char[] password) {
        if (envelope.length < HEADER_SIZE + 16 || envelope.length > MAX_BYTES)
            throw new SecretException("Invalid encrypted YAML size.");
        var buffer = ByteBuffer.wrap(envelope);
        byte[] magic = new byte[8], salt = new byte[16], nonce = new byte[12];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC) || buffer.getInt() != ITERATIONS)
            throw new SecretException("Unsupported encrypted YAML format.");
        buffer.get(salt);
        buffer.get(nonce);
        try {
            var cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce);
            cipher.updateAAD(Arrays.copyOf(envelope, HEADER_SIZE));
            return cipher.doFinal(envelope, HEADER_SIZE, envelope.length - HEADER_SIZE);
        } catch (GeneralSecurityException e) {
            throw new SecretException("Cannot unlock YAML: wrong password or damaged file.");
        }
    }

    private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] nonce) throws GeneralSecurityException {
        var spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher;
        } finally {
            spec.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] readLimited(Path input) throws IOException {
        try (var stream = Files.newInputStream(input)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new SecretException("Private configuration exceeds 1 MiB.");
            return bytes;
        }
    }
}
