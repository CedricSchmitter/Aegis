package dev.aegis.documents.application;

import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationCommand;
import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationResult;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.OriginalStore.StoredOriginal;
import dev.aegis.documents.application.ports.PageCache;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfPages.Placement;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.ScanStore;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.OriginalFormat;
import dev.aegis.documents.domain.TitleOrigin;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.PageEdit;
import dev.aegis.documents.domain.pages.PageEditOutput;
import dev.aegis.documents.domain.pages.PageRef;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carries out a page editor operation in its background job (ADR-0024). Every output is written by the PDF library,
 * staged in ingress and registered through the capture path with the id the operation derives for it, so an output
 * registered by an earlier attempt is recognized and not created again. Archive reads that fail are retried with a
 * bound; a refused source or an output above the upload limit fails the operation with its code. Outputs created
 * before a failure stay: each is a complete document with its derivation.
 */
public class PageEditRunner {
    private static final String SCAN_KEY = "scan";

    public sealed interface Outcome permits Done, Retry {
    }

    /** Nothing more to do; {@code reason} describes how the operation ended, for the job's log. */
    public record Done(String reason) implements Outcome {
    }

    /** Try again after a backoff for {@code attempts} failed attempts; {@code reason} is for the job's log. */
    public record Retry(int attempts, String reason) implements Outcome {
    }

    private final PageEditSteps steps;
    private final DocumentRepository documents;
    private final PageSources sources;
    private final ScanStore scans;
    private final PdfPages pdf;
    private final OriginalStore store;
    private final PageCache cache;
    private final PageEditSettings settings;
    private final AnalysisSettings analysis;
    private final Clock clock;

    public PageEditRunner(PageEditSteps steps, DocumentRepository documents, PageSources sources, ScanStore scans,
            PdfPages pdf, OriginalStore store, PageCache cache, PageEditSettings settings, AnalysisSettings analysis,
            Clock clock) {
        this.steps = steps;
        this.documents = documents;
        this.sources = sources;
        this.scans = scans;
        this.pdf = pdf;
        this.store = store;
        this.cache = cache;
        this.settings = settings;
        this.analysis = analysis;
        this.clock = clock;
    }

    public Outcome run(UUID id) {
        PageEdit edit = steps.pending(id).orElse(null);
        if (edit == null) {
            return new Done("not pending");
        }
        try {
            Map<String, Path> files = new HashMap<>();
            Map<String, PageSources.Source> resolved = new HashMap<>();
            List<PageEdit.Result> results = new ArrayList<>();
            for (int position = 0; position < edit.plan().outputs().size(); position++) {
                results.add(createOutput(edit, position, files, resolved));
            }
            steps.complete(id, results);
            deleteScan(edit);
            return new Done("created " + results.size() + " document(s)");
        } catch (ArchiveUnavailableException | OriginalUnavailableException unavailable) {
            return afterFailure(edit, "paperless.unavailable", true, unavailable.getMessage());
        } catch (PdfRefusedException refused) {
            return afterFailure(edit, refused.code(), false, refused.getMessage());
        } catch (InvalidInputException invalid) {
            return afterFailure(edit, invalid.code(), false, invalid.getMessage());
        } catch (IOException e) {
            return afterFailure(edit, "pageEdit.io", true, e.getMessage());
        }
    }

    private PageEdit.Result createOutput(PageEdit edit, int position, Map<String, Path> files,
            Map<String, PageSources.Source> resolved) throws IOException {
        DocumentId resultId = edit.resultIdFor(position);
        PageEditOutput output = edit.plan().outputs().get(position);
        Derivation derivation = Derivation.of(resultId, edit.id(), edit.requestedBy(), edit.requestedAt(),
                output.pages());
        if (documents.findById(resultId).isPresent()) {
            // Registered by an earlier attempt; registering again only adds a derivation that went missing.
            steps.registerOutput(command(edit, output, resultId, null), derivation);
            return new PageEdit.Result(position, resultId, false);
        }
        List<Placement> placements = new ArrayList<>();
        StringBuilder identity = new StringBuilder();
        for (PageRef page : output.pages()) {
            String key;
            boolean photo = false;
            Checksum checksum;
            if (page.fromScan()) {
                PageEdit.Scan scan = edit.scan().orElseThrow();
                key = SCAN_KEY;
                checksum = scan.checksum();
                if (!files.containsKey(SCAN_KEY)) {
                    if (!scans.exists(scan.path())) {
                        throw new InvalidInputException("pageEdit.scan.missing", "The scanned pages are gone");
                    }
                    files.put(SCAN_KEY, scans.resolve(scan.path()));
                }
            } else {
                PageSources.Source source = source(page.document(), resolved);
                key = source.key();
                photo = source.kind() == PageSources.Kind.PHOTO;
                checksum = source.checksum();
                files.putIfAbsent(key, source.file());
            }
            placements.add(new Placement(key, page.pageIndex(), page.rotation(), photo));
            identity.append(key).append(':').append(checksum.sha256Hex()).append(':').append(page.pageIndex())
                    .append(':').append(page.rotation()).append('\n');
        }
        Path work = cache.newWorkFile();
        try {
            pdf.assemble(files, placements, fileId(identity.toString()), work);
            if (Files.size(work) > settings.maxOutputBytes()) {
                throw new InvalidInputException("document.tooLarge", "New document " + (position + 1)
                        + " exceeds " + settings.maxOutputBytes());
            }
            StoredOriginal stored;
            try (InputStream content = Files.newInputStream(work)) {
                stored = store.store(resultId, content);
            }
            if (stored.format() != OriginalFormat.PDF) {
                deleteQuietly(stored.stagingPath());
                throw new IllegalStateException("the PDF library wrote no PDF for " + resultId);
            }
            RegistrationResult registration = steps.registerOutput(command(edit, output, resultId, stored),
                    derivation);
            if (registration.duplicate()) {
                deleteQuietly(stored.stagingPath());
            }
            boolean existing = registration.duplicate() && !registration.document().id().equals(resultId);
            return new PageEdit.Result(position, registration.document().id(), existing);
        } finally {
            Files.deleteIfExists(work);
        }
    }

    private PageSources.Source source(DocumentId id, Map<String, PageSources.Source> resolved) throws IOException {
        PageSources.Source known = resolved.get(id.toString());
        if (known != null) {
            return known;
        }
        Document document = documents.findById(id).orElse(null);
        if (document == null || document.isPurgePending()) {
            throw new InvalidInputException("pageEdit.source.gone", "Source " + id + " is being deleted");
        }
        PageSources.Source source = sources.resolve(document);
        resolved.put(id.toString(), source);
        return source;
    }

    /**
     * The registration of an output. A missing {@code stored} file is only valid for an output registered before,
     * whose registration finds the id and changes nothing but a missing derivation.
     */
    private RegistrationCommand command(PageEdit edit, PageEditOutput output, DocumentId id, StoredOriginal stored) {
        String filename = DocumentCaptureService.safeFilename(output.title() + ".pdf");
        Document known = stored == null ? documents.findById(id).orElseThrow() : null;
        return new RegistrationCommand(id, output.title(),
                output.titleChosen() ? TitleOrigin.MANUAL : TitleOrigin.FILENAME, filename,
                OriginalFormat.PDF.contentType(), stored == null ? known.sizeBytes() : stored.sizeBytes(),
                stored == null ? known.checksum() : stored.checksum(), edit.requestedBy(), clock.instant(),
                output.subjects(), stored == null ? known.stagingPath() : stored.stagingPath(), output.categoryId(),
                analysis.automatic(), null);
    }

    private Outcome afterFailure(PageEdit edit, String code, boolean retryable, String detail) {
        PageEditSteps.FailureDecision decision = steps.recordFailure(edit.id(), code, retryable);
        String reason = code + " (" + detail + ")";
        if (decision.retry()) {
            return new Retry(decision.attempts(), reason);
        }
        deleteScan(edit);
        return new Done("failed: " + reason);
    }

    private void deleteScan(PageEdit edit) {
        edit.scan().ifPresent(scan -> {
            try {
                scans.delete(scan.path());
            } catch (IOException ignored) {
                // A leftover scan file holds no record; it is only disk space.
            }
        });
    }

    private void deleteQuietly(String stagingPath) {
        try {
            store.delete(stagingPath);
        } catch (IOException ignored) {
            // The reconciliation job quarantines leftover files without a record.
        }
    }

    /** Sixteen bytes of the SHA-256 of what the output consists of, the same for the same sources and pages. */
    static byte[] fileId(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
