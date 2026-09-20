package dev.aegis.bootstrap.config;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built single-page application: an existing file wins, a history-API route falls back to
 * {@code index.html}. Paths that address the server rather than the app -- {@code /api}, {@code /actuator} and
 * anything that looks like a file -- resolve to nothing, so the handler reports a missing resource and
 * {@link ProblemDetailsAdvice} turns it into a 404 problem detail instead of the app shell.
 */
class SpaResourceResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource requested = location.createRelative(resourcePath);
        if (requested.exists() && requested.isReadable()) {
            return requested;
        }
        if (addressesTheServer(resourcePath) || resourcePath.contains(".")) {
            return null;
        }
        Resource index = location.createRelative("index.html");
        return index.exists() && index.isReadable() ? index : null;
    }

    /** The server's own prefixes, with or without a path below them; never a route of the app. */
    private static boolean addressesTheServer(String resourcePath) {
        return resourcePath.equals("api") || resourcePath.startsWith("api/")
                || resourcePath.equals("actuator") || resourcePath.startsWith("actuator/");
    }
}
