package dev.aegis.shared;

/**
 * Raised when a shared edit targets an outdated version. The caller must reload and decide; Aegis never silently
 * overwrites the other member's change. Mapped to HTTP 409 at the boundary.
 */
public class StaleVersionException extends RuntimeException {
    private final String resource;
    private final String id;
    private final long expectedVersion;
    private final long actualVersion;

    public StaleVersionException(String resource, String id, long expectedVersion, long actualVersion) {
        super(resource + " " + id + " changed: expected version " + expectedVersion + " but found " + actualVersion);
        this.resource = resource;
        this.id = id;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String resource() {
        return resource;
    }

    public String id() {
        return id;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
