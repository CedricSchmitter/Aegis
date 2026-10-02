package dev.aegis.documents.adapters.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.ports.PdfPages.Inspection;
import dev.aegis.documents.application.ports.PdfPages.PageSize;
import dev.aegis.documents.application.ports.PdfPages.Placement;
import dev.aegis.documents.application.ports.PdfRefusedException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPagesTest {
    private static final byte[] FILE_ID = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private final PdfBoxPages pages = new PdfBoxPages();

    @TempDir
    Path dir;

    /** A letter with uncompressed page contents, so the raw output shows which pages it carries. */
    private Path letter(String name, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, false)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    content.newLineAtOffset(72, 700);
                    content.showText(name + " page " + i + " marker");
                    content.endText();
                }
            }
            return save(document, name + ".pdf");
        }
    }

    private Path save(PDDocument document, String name) throws IOException {
        Path file = dir.resolve(name);
        document.save(file.toFile());
        return file;
    }

    private Path assemble(Map<String, Path> sources, List<Placement> placements, String name) throws IOException {
        Path target = dir.resolve(name);
        pages.assemble(sources, placements, FILE_ID, target);
        return target;
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String raw(Path pdf) throws IOException {
        return new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
    }

    @Test
    void inspectionReadsPagesAsDisplayedAndFindsAttachments() throws IOException {
        Path plain = letter("plain", 2);
        try (PDDocument document = Loader.loadPDF(plain.toFile())) {
            document.getPage(1).setRotation(90);
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("invoice.xml");
            spec.setEmbeddedFile(new PDEmbeddedFile(document, new ByteArrayInputStream("<x/>".getBytes())));
            PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
            files.setNames(Map.of("invoice.xml", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(files);
            document.getDocumentCatalog().setNames(names);
            save(document, "invoice.pdf");
        }

        Inspection inspection = pages.inspect(plain);
        assertThat(inspection.pageCount()).isEqualTo(2);
        assertThat(inspection.signed()).isFalse();
        assertThat(inspection.embeddedFiles()).isFalse();

        Inspection invoice = pages.inspect(dir.resolve("invoice.pdf"));
        assertThat(invoice.embeddedFiles()).isTrue();
        PageSize portrait = invoice.pages().get(0);
        PageSize turned = invoice.pages().get(1);
        assertThat(portrait.height()).isGreaterThan(portrait.width());
        assertThat(turned.width()).isEqualTo(portrait.height());
    }

    @Test
    void encryptedAndDamagedFilesAreRefusedWithTheirCode() throws IOException {
        Path locked = letter("locked", 1);
        try (PDDocument document = Loader.loadPDF(locked.toFile())) {
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            save(document, "locked.pdf");
        }
        Path damaged = Files.write(dir.resolve("damaged.pdf"), "%PDF-1.7\nnot really".getBytes());

        assertThatThrownBy(() -> pages.inspect(locked)).isInstanceOfSatisfying(PdfRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(PdfRefusedException.ENCRYPTED));
        assertThatThrownBy(() -> pages.inspect(damaged)).isInstanceOfSatisfying(PdfRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(PdfRefusedException.UNREADABLE));
    }

    @Test
    void aRestrictedPdfThatOpensWithoutPasswordGivesAnUnencryptedCopy() throws IOException {
        Path restricted = letter("restricted", 2);
        try (PDDocument document = Loader.loadPDF(restricted.toFile())) {
            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            permissions.setCanExtractContent(false);
            document.protect(new StandardProtectionPolicy("owner", "", permissions));
            save(document, "restricted.pdf");
        }

        Path output = assemble(Map.of("r", restricted), List.of(new Placement("r", 1, 0, false)), "copy.pdf");

        try (PDDocument copy = Loader.loadPDF(output.toFile())) {
            assertThat(copy.isEncrypted()).isFalse();
            assertThat(copy.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(text(output)).contains("restricted page 2 marker");
    }

    @Test
    void splittingCarriesOnlyTheChosenPagesEvenWithLinksAndFormFields() throws IOException {
        Path source = letter("scan", 3);
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDPage second = document.getPage(1);
            PDPage third = document.getPage(2);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 72, 100, 20));
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(third);
            link.setDestination(destination);
            COSDictionary field = new COSDictionary();
            field.setItem(COSName.FT, COSName.getPDFName("Tx"));
            field.setString(COSName.T, "name");
            PDAnnotationWidget onSecond = widget(second, field);
            PDAnnotationWidget onThird = widget(third, field);
            COSArray kids = new COSArray();
            kids.add(onSecond);
            kids.add(onThird);
            field.setItem(COSName.KIDS, kids);
            second.setAnnotations(List.of(link, onSecond));
            third.setAnnotations(List.of(onThird));
            save(document, "scan.pdf");
        }

        Path output = assemble(Map.of("s", source), List.of(new Placement("s", 1, 0, false)), "part.pdf");

        assertThat(raw(output)).contains("scan page 2 marker").doesNotContain("scan page 3 marker")
                .doesNotContain("scan page 1 marker");
        try (PDDocument part = Loader.loadPDF(output.toFile())) {
            assertThat(part.getNumberOfPages()).isEqualTo(1);
            assertThat(part.getPage(0).getAnnotations()).hasSize(1)
                    .allSatisfy(annotation -> assertThat(annotation.getCOSObject().containsKey(COSName.PARENT))
                            .isFalse());
        }
    }

    private static PDAnnotationWidget widget(PDPage page, COSDictionary field) {
        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(new PDRectangle(72, 400, 200, 20));
        widget.setPage(page);
        widget.getCOSObject().setItem(COSName.PARENT, field);
        return widget;
    }

    @Test
    void mergingReordersAndTurnsPagesOfSeveralSources() throws IOException {
        Path contract = letter("contract", 2);
        Path annex = letter("annex", 1);

        Path output = assemble(Map.of("c", contract, "a", annex), List.of(new Placement("c", 1, 0, false),
                new Placement("a", 0, 90, false), new Placement("c", 0, 270, false)), "merged.pdf");

        try (PDDocument merged = Loader.loadPDF(output.toFile())) {
            assertThat(merged.getNumberOfPages()).isEqualTo(3);
            assertThat(merged.getPage(0).getRotation()).isZero();
            assertThat(merged.getPage(1).getRotation()).isEqualTo(90);
            assertThat(merged.getPage(2).getRotation()).isEqualTo(270);
            assertThat(content(merged.getPage(0))).contains("contract page 2 marker");
            assertThat(content(merged.getPage(1))).contains("annex page 1 marker");
            assertThat(content(merged.getPage(2))).contains("contract page 1 marker");
        }
    }

    private static String content(PDPage page) throws IOException {
        try (var in = page.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theSamePagesAndFileIdAlwaysGiveTheSameBytes() throws IOException {
        Path source = letter("same", 3);
        List<Placement> placements = List.of(new Placement("s", 2, 0, false), new Placement("s", 0, 180, false));

        Path first = assemble(Map.of("s", source), placements, "first.pdf");
        Path second = assemble(Map.of("s", source), placements, "second.pdf");
        Path otherId = dir.resolve("other.pdf");
        pages.assemble(Map.of("s", source), placements, "fedcba9876543210".getBytes(), otherId);

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        assertThat(Files.readAllBytes(first)).isNotEqualTo(Files.readAllBytes(otherId));
    }

    @Test
    void aPhotoRenditionSizedFrom72DpiIsShrunkToA4() throws IOException {
        Path rendition;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(3000, 4000));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(100, 100, 2800, 3800);
                content.fill();
            }
            rendition = save(document, "photo.pdf");
        }

        Path output = assemble(Map.of("p", rendition), List.of(new Placement("p", 0, 0, true)), "fitted.pdf");
        Path unchanged = assemble(Map.of("p", rendition), List.of(new Placement("p", 0, 0, false)), "kept.pdf");

        assertThat(pages.inspect(output).pages().get(0).height()).isCloseTo(841.89f,
                org.assertj.core.data.Offset.offset(0.1f));
        assertThat(pages.inspect(unchanged).pages().get(0).height()).isEqualTo(4000f);
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(pages.renderPreview(output, 0, 200)));
        // The scaled content still fills the page: the centre is the black rectangle, not white paper.
        assertThat(preview.getRGB(preview.getWidth() / 2, preview.getHeight() / 2) & 0xFFFFFF).isLessThan(0x202020);
    }

    @Test
    void previewsHaveTheRequestedLongSideAndFollowThePageRotation() throws IOException {
        Path source = letter("preview", 2);
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            document.getPage(1).setRotation(90);
            save(document, "preview.pdf");
        }

        BufferedImage portrait = ImageIO.read(new ByteArrayInputStream(pages.renderPreview(source, 0, 480)));
        BufferedImage landscape = ImageIO.read(new ByteArrayInputStream(pages.renderPreview(source, 1, 480)));

        assertThat(portrait.getHeight()).isEqualTo(480);
        assertThat(portrait.getWidth()).isLessThan(480);
        assertThat(landscape.getWidth()).isEqualTo(480);
        assertThat(landscape.getHeight()).isLessThan(480);
    }
}
