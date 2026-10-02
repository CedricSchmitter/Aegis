package dev.aegis.documents.adapters.pdf;

import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfRefusedException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * The page editor's PDF work with Apache PDFBox (ADR-0024). Sources are opened from their files with temporary-file
 * buffering, so a large original does not have to fit into memory.
 *
 * <p>A page is copied by reference: the new page dictionary is a shallow copy that points at the source's content
 * streams and resources, which are written into the new file. Everything that would pull the rest of the source
 * along is left out: the page's parent, article beads and structure references, links to other pages, popups and the
 * form field tree behind widgets (a filled field keeps its appearance). The output carries no encryption, signature,
 * attachments, outline or metadata of a source, and no dates, so its bytes depend only on its pages and file id.
 */
@Component
public class PdfBoxPages implements PdfPages {
    /** Long side of A4 in points; photo renditions larger than this are shrunk to it. */
    static final float A4_LONG_SIDE = 841.89f;
    private static final float JPEG_QUALITY = 0.8f;

    @Override
    public Inspection inspect(Path pdf) throws IOException {
        try (PDDocument document = open(pdf)) {
            List<PageSize> pages = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                pages.add(displayedSize(page));
            }
            if (pages.isEmpty()) {
                throw new PdfRefusedException(PdfRefusedException.UNREADABLE, "the PDF has no pages");
            }
            PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
            boolean embedded = names != null && names.getEmbeddedFiles() != null;
            return new Inspection(pages, !document.getSignatureDictionaries().isEmpty(), embedded);
        }
    }

    @Override
    public byte[] renderPreview(Path pdf, int pageIndex, int longSidePixels) throws IOException {
        try (PDDocument document = open(pdf)) {
            PageSize size = displayedSize(document.getPage(pageIndex));
            float scale = longSidePixels / Math.max(size.width(), size.height());
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(pageIndex, scale, ImageType.RGB);
            return jpeg(image);
        }
    }

    @Override
    public void assemble(Map<String, Path> sources, List<Placement> pages, byte[] fileId, Path target)
            throws IOException {
        Map<String, PDDocument> opened = new HashMap<>();
        try (PDDocument output = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            float version = 1.4f;
            for (Placement placement : pages) {
                PDDocument source = opened.get(placement.source());
                if (source == null) {
                    Path file = sources.get(placement.source());
                    if (file == null) {
                        throw new IllegalArgumentException("no source file for " + placement.source());
                    }
                    source = open(file);
                    opened.put(placement.source(), source);
                }
                version = Math.max(version, source.getVersion());
                copyPage(output, source.getPage(placement.pageIndex()), placement);
            }
            output.setVersion(version);
            COSArray id = new COSArray();
            id.add(new COSString(fileId));
            id.add(new COSString(fileId));
            output.getDocument().getTrailer().setItem(COSName.ID, id);
            // The sources stay open until the output is written: its pages refer to their objects.
            output.save(target.toFile());
        } finally {
            for (PDDocument source : opened.values()) {
                source.close();
            }
        }
    }

    private static PDDocument open(Path pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf.toFile(), "", IOUtils.createTempFileOnlyStreamCache());
        } catch (InvalidPasswordException encrypted) {
            throw new PdfRefusedException(PdfRefusedException.ENCRYPTED, "the PDF needs a password");
        } catch (IOException damaged) {
            if (!pdf.toFile().isFile()) {
                throw damaged;
            }
            throw new PdfRefusedException(PdfRefusedException.UNREADABLE, "the PDF cannot be read: "
                    + damaged.getMessage());
        }
    }

    private static PageSize displayedSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        boolean turned = page.getRotation() % 180 != 0;
        return turned ? new PageSize(box.getHeight(), box.getWidth()) : new PageSize(box.getWidth(), box.getHeight());
    }

    private static void copyPage(PDDocument output, PDPage source, Placement placement) throws IOException {
        COSDictionary dictionary = new COSDictionary(source.getCOSObject());
        for (COSName key : List.of(COSName.PARENT, COSName.B, COSName.STRUCT_PARENTS, COSName.ANNOTS,
                COSName.getPDFName("Thumb"), COSName.getPDFName("PieceInfo"))) {
            dictionary.removeItem(key);
        }
        PDPage page = new PDPage(dictionary);
        // Inherited attributes become the page's own, since the new page tree has no parent to inherit from.
        page.setMediaBox(source.getMediaBox());
        if (!sameBox(source.getCropBox(), source.getMediaBox())) {
            page.setCropBox(source.getCropBox());
        }
        page.setResources(source.getResources());
        page.setRotation((source.getRotation() + placement.rotation()) % 360);
        PageSize size = displayedSize(source);
        float longSide = Math.max(size.width(), size.height());
        if (placement.fitToA4() && longSide > A4_LONG_SIDE * 1.01f) {
            scale(output, page, A4_LONG_SIDE / longSide);
        } else {
            page.setAnnotations(copyAnnotations(source));
        }
        output.addPage(page);
    }

    private static boolean sameBox(PDRectangle first, PDRectangle second) {
        return first.getLowerLeftX() == second.getLowerLeftX() && first.getLowerLeftY() == second.getLowerLeftY()
                && first.getUpperRightX() == second.getUpperRightX()
                && first.getUpperRightY() == second.getUpperRightY();
    }

    /**
     * Shrinks a page by wrapping its content in a scaling transformation and scaling its boxes. Used for photo
     * renditions only, which carry no annotations whose rectangles would have to follow.
     */
    private static void scale(PDDocument output, PDPage page, float factor) throws IOException {
        String number = String.format(Locale.ROOT, "%.6f", factor);
        PDStream before = stream(output, "q " + number + " 0 0 " + number + " 0 0 cm\n");
        PDStream after = stream(output, "\nQ\n");
        COSArray contents = new COSArray();
        contents.add(before);
        COSBase existing = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (existing instanceof COSArray array) {
            for (COSBase part : array) {
                contents.add(part);
            }
        } else if (existing != null) {
            contents.add(page.getCOSObject().getItem(COSName.CONTENTS));
        }
        contents.add(after);
        page.getCOSObject().setItem(COSName.CONTENTS, contents);
        page.setMediaBox(scaled(page.getMediaBox(), factor));
        if (page.getCOSObject().getDictionaryObject(COSName.CROP_BOX) != null) {
            page.setCropBox(scaled(page.getCropBox(), factor));
        }
        page.getCOSObject().removeItem(COSName.ANNOTS);
    }

    private static PDRectangle scaled(PDRectangle box, float factor) {
        return new PDRectangle(box.getLowerLeftX() * factor, box.getLowerLeftY() * factor, box.getWidth() * factor,
                box.getHeight() * factor);
    }

    private static PDStream stream(PDDocument output, String operators) throws IOException {
        PDStream stream = new PDStream(output);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(operators.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static List<PDAnnotation> copyAnnotations(PDPage source) throws IOException {
        List<PDAnnotation> copies = new ArrayList<>();
        for (PDAnnotation annotation : source.getAnnotations()) {
            if (annotation instanceof PDAnnotationPopup || linksToPage(annotation)) {
                continue;
            }
            COSDictionary copy = new COSDictionary(annotation.getCOSObject());
            for (COSName key : List.of(COSName.P, COSName.PARENT, COSName.POPUP, COSName.IRT,
                    COSName.STRUCT_PARENT, COSName.AA)) {
                copy.removeItem(key);
            }
            copies.add(PDAnnotation.createAnnotation(copy));
        }
        return copies;
    }

    private static boolean linksToPage(PDAnnotation annotation) throws IOException {
        if (!(annotation instanceof PDAnnotationLink link)) {
            return false;
        }
        if (link.getDestination() instanceof PDPageDestination) {
            return true;
        }
        PDAction action = link.getAction();
        return action instanceof PDActionGoTo goTo && goTo.getDestination() instanceof PDPageDestination;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
