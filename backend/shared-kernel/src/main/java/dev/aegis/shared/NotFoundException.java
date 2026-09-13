package dev.aegis.shared;

/** Raised when a referenced record does not exist. Mapped to HTTP 404 at the boundary. */
public class NotFoundException extends RuntimeException {
    private final String resource;
    private final String id;

    public NotFoundException(String resource, String id) {
        super(resource + " " + id + " not found");
        this.resource = resource;
        this.id = id;
    }

    public String resource() {
        return resource;
    }

    public String id() {
        return id;
    }
}
