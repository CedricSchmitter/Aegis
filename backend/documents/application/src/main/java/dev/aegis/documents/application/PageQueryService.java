package dev.aegis.documents.application;

import dev.aegis.documents.application.api.PageQueries;
import dev.aegis.documents.application.ports.PageCache;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfPages.Inspection;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Pages, previews and source relationships for the page editor (ADR-0024). Previews are rendered once per page and
 * size and then served from the page cache; at most two renderings run at the same time, so a phone scrolling through
 * a long document does not occupy every processor.
 */
public class PageQueryService implements PageQueries {
    private static final String TRASHED = "pageEdit.source.trashed";

    private final DocumentRepository documents;
    private final DerivationRepository derivations;
    private final PageSources sources;
    private final PageCache cache;
    private final PdfPages pdf;
    private final HouseholdAccess access;
    private final PageEditSettings settings;
    private final Semaphore renderings = new Semaphore(2);

    public PageQueryService(DocumentRepository documents, DerivationRepository derivations, PageSources sources,
            PageCache cache, PdfPages pdf, HouseholdAccess access, PageEditSettings settings) {
        this.documents = documents;
        this.derivations = derivations;
        this.sources = sources;
        this.cache = cache;
        this.pdf = pdf;
        this.access = access;
        this.settings = settings;
    }

    @Override
    public PagesView pages(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        String kind = PageSources.kindOf(document).name();
        try {
            Inspection inspection = sources.inspect(sources.resolve(document));
            List<PageView> pages = new ArrayList<>();
            for (int index = 0; index < inspection.pageCount(); index++) {
                PdfPages.PageSize size = inspection.pages().get(index);
                pages.add(new PageView(index, size.width(), size.height()));
            }
            List<String> warnings = new ArrayList<>();
            if (inspection.signed()) {
                warnings.add(WARNING_SIGNED);
            }
            if (inspection.embeddedFiles()) {
                warnings.add(WARNING_EMBEDDED_FILES);
            }
            return new PagesView(id, kind, pages, warnings, document.isTrashed() ? TRASHED : null);
        } catch (PdfRefusedException refused) {
            return new PagesView(id, kind, List.of(), List.of(), refused.code());
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the pages of " + id, e);
        }
    }

    @Override
    public byte[] preview(Actor actor, DocumentId id, int pageIndex) {
        access.requireMember(actor);
        Document document = load(id);
        int size = settings.previewLongSide();
        try {
            PageSources.Source source = sources.resolve(document);
            if (pageIndex < 0 || pageIndex >= sources.inspect(source).pageCount()) {
                throw new NotFoundException("page", id + "/" + pageIndex);
            }
            Optional<byte[]> cached = cache.findPreview(source.key(), pageIndex, size);
            if (cached.isPresent()) {
                return cached.get();
            }
            renderings.acquireUninterruptibly();
            try {
                byte[] jpeg = pdf.renderPreview(source.file(), pageIndex, size);
                cache.storePreview(source.key(), pageIndex, size, jpeg);
                return jpeg;
            } finally {
                renderings.release();
            }
        } catch (PdfRefusedException refused) {
            throw new NotFoundException("page", id + "/" + pageIndex);
        } catch (IOException e) {
            throw new UncheckedIOException("could not render page " + pageIndex + " of " + id, e);
        }
    }

    @Override
    public DerivationsView derivations(Actor actor, DocumentId id) {
        access.requireMember(actor);
        load(id);
        OriginView origin = derivations.findByDocument(id).map(this::origin).orElse(null);
        List<DerivedView> derived = new ArrayList<>();
        for (Derivation derivation : derivations.findBySource(id)) {
            documents.findById(derivation.document()).ifPresent(result -> derived.add(new DerivedView(result.id(),
                    result.title(), result.isTrashed(), derivation.createdBy(), derivation.createdAt(),
                    derivation.ranges().stream().filter(range -> id.equals(range.source()))
                            .map(range -> new PageSpan(range.firstSourcePage(), range.lastSourcePage())).toList())));
        }
        return new DerivationsView(origin, derived);
    }

    private OriginView origin(Derivation derivation) {
        List<SourceRangeView> ranges = new ArrayList<>();
        for (Derivation.Range range : derivation.ranges()) {
            Document source = range.source() == null ? null : documents.findById(range.source()).orElse(null);
            String state = range.kind() == Derivation.SourceKind.SCAN ? null
                    : source == null ? SourceRangeView.DELETED
                    : source.isTrashed() ? SourceRangeView.TRASHED : SourceRangeView.AVAILABLE;
            ranges.add(new SourceRangeView(range.firstPage(), range.lastPage(), range.kind().name(),
                    source == null ? null : source.id(), source == null ? null : source.title(), state,
                    range.firstSourcePage(), range.lastSourcePage(), range.rotation()));
        }
        return new OriginView(derivation.pageEdit(), derivation.createdBy(), derivation.createdAt(), ranges);
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
