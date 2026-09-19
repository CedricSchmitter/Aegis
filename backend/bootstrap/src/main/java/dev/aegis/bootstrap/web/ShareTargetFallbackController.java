package dev.aegis.bootstrap.web;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Android delivers shares to the installed web app as a POST to {@code /share-target}. The frontend's service worker
 * normally answers that POST on the phone and keeps the files for an authenticated upload. This fallback is reached
 * only when no worker was active, e.g. after the browser's site data was cleared. It reads and stores nothing, which
 * is why it needs no CSRF token, and sends the phone to the share page, which registers the worker again, so the
 * next share works.
 */
@Hidden
@RestController
public class ShareTargetFallbackController {
    public static final String PATH = "/share-target";

    @PostMapping(PATH)
    ResponseEntity<Void> shareWithoutServiceWorker() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/share?error=unavailable")).build();
    }
}
