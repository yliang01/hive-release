package cc.cc3c.hive.release;

/**
 * User-facing message must not contain raw credentials or signed URLs.
 */
public class ReleaseException extends RuntimeException {
    public ReleaseException(String message) {
        super(message);
    }
}
