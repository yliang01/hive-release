package cc.cc3c.hive.release.common;

/**
 * Messages must never contain configuration values, passwords or signed URLs.
 */
public final class SecretException extends RuntimeException {
    public SecretException(String message) {
        super(message);
    }
}
