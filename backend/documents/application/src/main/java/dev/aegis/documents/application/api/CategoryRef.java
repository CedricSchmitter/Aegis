package dev.aegis.documents.application.api;

import java.util.UUID;

/** One step of a folder path (breadcrumb). */
public record CategoryRef(UUID id, String name) {
}
